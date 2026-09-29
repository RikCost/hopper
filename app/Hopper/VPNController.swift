import Foundation
import NetworkExtension

struct ServerUpdatePrompt: Identifiable, Equatable {
    let id = UUID()
    let hops: [HopNodeProfile]
    let targetVersion: String
}

struct ChainImportPrompt: Identifiable, Equatable {
    let id = UUID()
    let chainID: UUID
    let message: String
}

@MainActor
final class VPNController: ObservableObject {
    @Published private(set) var state: AppState
    @Published private(set) var vpnStatus: NEVPNStatus = .invalid
    @Published private(set) var provisionStatus: String?
    @Published var errorMessage: String?
    @Published var serverUpdatePrompt: ServerUpdatePrompt?
    @Published var chainImportPrompt: ChainImportPrompt?
    @Published private(set) var chainStatusReports: [UUID: [ChainStatusReport]] = [:]
    /// External `.hopperconf` open (Files / share sheet) awaiting password + import.
    @Published var pendingHopperConfData: Data?
    @Published var pendingLanInvite: HopperLanInvite?

    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?
    private var pendingConnectRestart = false
    private var ignoreDisconnectError = false
    private var connectGeneration = 0

    init() {
        state = ProfileStore.load()
        Task { await reloadVPN() }
    }

    /// Apply a shared server or chain payload (new IDs minted for servers/chains).
    @discardableResult
    func importPayload(_ payload: HopperConf.Payload, announceChain: Bool = true) -> String {
        switch payload {
        case .server(let profile):
            state.addServer(profile)
            persist()
            return "Imported server \(profile.displayName)."
        case .chain(let name, let hops):
            var hopIDs: [UUID] = []
            for hop in hops {
                state.addServer(hop)
                hopIDs.append(hop.id)
            }
            let chainID = state.addChain(name: name)
            if let index = state.chains.firstIndex(where: { $0.id == chainID }) {
                state.chains[index].hopIDs = hopIDs
            }
            state.selectChain(chainID)
            persist()
            let label = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "Untitled chain" : name
            let message = "Imported chain \(label) with \(hops.count) server(s)."
            if announceChain {
                let prompt = ChainImportPrompt(chainID: chainID, message: message)
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 400_000_000)
                    self.chainImportPrompt = prompt
                }
            }
            return message
        case .key(let key):
            let stored = state.importDeployKey(key)
            persist()
            return "Imported key \(stored.displayName)."
        }
    }

    func dismissChainImportPrompt() {
        chainImportPrompt = nil
    }

    func handleIncomingHopperConfURL(_ url: URL) {
        if url.scheme?.lowercased() == HopperLanInvite.scheme {
            if let invite = HopperLanInvite.parse(url) {
                pendingLanInvite = invite
            } else {
                errorMessage = "Not a valid Hopper remote-import link."
            }
            return
        }
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed { url.stopAccessingSecurityScopedResource() }
        }
        do {
            let data = try Data(contentsOf: url)
            if HopperConf.isHopperConfFile(data) {
                pendingHopperConfData = data
            } else if let text = String(data: data, encoding: .utf8) {
                let payload = try HopperConf.parsePayloadJSON(text)
                _ = importPayload(payload)
            } else {
                errorMessage = HopperConf.ConfError.invalidEnvelope.localizedDescription
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Returns true when the scanner should dismiss (LAN invite or successful import).
    @discardableResult
    func handleScannedQR(_ text: String) -> Bool {
        if let invite = HopperLanInvite.parse(text) {
            pendingLanInvite = invite
            return true
        }
        do {
            let imported = try HopperConf.parsePayloadJSON(text)
            _ = importPayload(imported)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    var isConnected: Bool { vpnStatus == .connected }
    var isBusy: Bool { vpnStatus == .connecting || vpnStatus == .disconnecting }

    // MARK: - Servers

    func addServer(_ server: HopNodeProfile) {
        state.addServer(server)
        persist()
    }

    func addDeployKey(_ key: DeploySSHKey) {
        state.addDeployKey(key)
        persist()
    }

    func recordDeployKeyUse(id: UUID, server: HopNodeProfile) {
        state.recordDeployKeyUse(id: id, server: server)
        persist()
    }

    func importDeployKey(_ key: DeploySSHKey) {
        _ = state.importDeployKey(key)
        persist()
    }

    func deployServer(
        host: String,
        port: Int,
        user: String,
        auth: ServerDeployAuth,
        onLog: (@Sendable (String) -> Void)? = nil
    ) async throws {
        let result = try await ServerDeployer.deploy(
            host: host,
            port: port,
            user: user,
            auth: auth,
            onLog: onLog
        )
        if var newKey = result.newDeployKey {
            newKey.recordAssignment(from: result.profile)
            state.addDeployKey(newKey)
        } else if case .deployKey(let key) = auth {
            state.recordDeployKeyUse(id: key.id, server: result.profile)
        }
        state.addServer(result.profile)
        persist()
    }

    func deleteDeployKey(id: UUID) {
        state.removeDeployKey(id: id)
        persist()
    }

    func renameDeployKey(id: UUID, name: String) {
        state.renameDeployKey(id: id, name: name)
        persist()
    }

    func deleteServers(at offsets: IndexSet) {
        state.removeServers(at: offsets)
        persist()
    }

    func deleteServer(id: UUID) {
        guard let index = state.servers.firstIndex(where: { $0.id == id }) else { return }
        deleteServers(at: IndexSet(integer: index))
    }

    func renameServer(id: UUID, name: String) {
        state.renameServer(id: id, name: name)
        persist()
    }

    // MARK: - Chains

    @discardableResult
    func addChain(name: String = "") -> UUID {
        let id = state.addChain(name: name)
        persist()
        return id
    }

    func deleteChains(at offsets: IndexSet) {
        state.removeChains(at: offsets)
        persist()
    }

    func selectChain(_ id: UUID?) {
        state.selectChain(id)
        persist()
    }

    func renameChain(id: UUID, name: String) {
        state.renameChain(id: id, name: name)
        persist()
    }

    func moveHopInChain(chainID: UUID, from source: IndexSet, to destination: Int) {
        state.moveHopInChain(chainID: chainID, from: source, to: destination)
        persist()
    }

    func addServerToChain(chainID: UUID, serverID: UUID) {
        state.addServerToChain(chainID: chainID, serverID: serverID)
        persist()
    }

    func removeHopFromChain(chainID: UUID, at offsets: IndexSet) {
        state.removeHopFromChain(chainID: chainID, at: offsets)
        persist()
    }

    // MARK: - Status

    func fetchChainStatus(chainID: UUID) async {
        guard let chain = state.chains.first(where: { $0.id == chainID }) else { return }
        let hops = state.resolveHops(chain)
        guard !hops.isEmpty else {
            chainStatusReports[chainID] = []
            return
        }
        do {
            let reports = try await ChainSSHForward.withChain(hops) { forward in
                var reports: [ChainStatusReport] = []
                for index in hops.indices {
                    if let report = try? await ChainStatusService.fetch(
                        on: forward.client(at: index),
                        hop: hops[index],
                        chainID: chainID
                    ) {
                        reports.append(report)
                    }
                }
                return reports
            }
            chainStatusReports[chainID] = reports
        } catch {
            TunnelLog.error("Chain status through entry failed: \(HopErrorDetails.describe(error))")
        }
    }

    // MARK: - VPN

    func connect(restartHopperd: Bool = false) async {
        guard let chain = state.selectedChain, let entry = state.entryHop else {
            reportError("Select a chain with at least one server (entry → exit).")
            return
        }
        connectGeneration += 1
        let generation = connectGeneration
        ignoreDisconnectError = false
        errorMessage = nil
        provisionStatus = nil
        ProfileStore.clearLastTunnelError()

        let hops = state.activeHops
        do {
            provisionStatus = "Checking server versions…"
            let versionOutcome = try await preflightVersions(hops: hops)
            guard generation == connectGeneration else { return }
            switch versionOutcome {
            case .appTooOld(let required):
                provisionStatus = nil
                reportError("App version \(HopVersion.appVersion) is too old. Update to \(required) or newer.")
                return
            case .serverTooOld(let outdated):
                provisionStatus = nil
                serverUpdatePrompt = ServerUpdatePrompt(
                    hops: outdated,
                    targetVersion: HopVersion.manifest.version
                )
                pendingConnectRestart = restartHopperd
                return
            case .compatible:
                break
            }

            try await performConnect(chain: chain, entry: entry, hops: hops, restartHopperd: restartHopperd)
        } catch {
            guard generation == connectGeneration else { return }
            provisionStatus = nil
            reportError(HopErrorDetails.describe(error))
        }
    }

    func confirmServerUpdate() async {
        guard let prompt = serverUpdatePrompt else { return }
        serverUpdatePrompt = nil
        errorMessage = nil
        provisionStatus = "Updating servers…"
        do {
            let hops = state.activeHops
            try await ChainSSHForward.withChain(hops) { forward in
                for hop in prompt.hops {
                    guard let index = hops.firstIndex(where: { $0.id == hop.id }) else { continue }
                    try await VersionService.updateServer(
                        on: forward.client(at: index),
                        hop: hop,
                        to: prompt.targetVersion
                    )
                }
            }
            guard let chain = state.selectedChain, let entry = state.entryHop else { return }
            try await performConnect(
                chain: chain,
                entry: entry,
                hops: state.activeHops,
                restartHopperd: pendingConnectRestart
            )
        } catch {
            provisionStatus = nil
            reportError(HopErrorDetails.describe(error))
        }
    }

    func cancelServerUpdate() {
        serverUpdatePrompt = nil
        pendingConnectRestart = false
    }

    private func performConnect(
        chain: HopChain,
        entry: HopNodeProfile,
        hops: [HopNodeProfile],
        restartHopperd: Bool
    ) async throws {
        provisionStatus = "Connecting to \(entry.displayName)…"
        _ = try await ChainProvisioner.provision(
            chainID: chain.id,
            chain: hops,
            restartHopperd: restartHopperd
        ) { [weak self] index, total, message in
            Task { @MainActor in
                self?.provisionStatus = "[\(index + 1)/\(total)] \(message)"
            }
        }
        provisionStatus = "Starting VPN…"

        let context = TunnelConnectContext(
            chainID: chain.id,
            hopperPort: ChainTopology.listenPort(chainID: chain.id),
            overlayCIDR: ChainTopology.overlayCIDR(chainID: chain.id),
            deviceID: ProfileStore.deviceID()
        )

        let manager = try await ensureManager(hop: entry, context: context)
        self.manager = manager
        observeStatus()
        try manager.connection.startVPNTunnel(options: TunnelBootstrap.options(hop: entry, context: context))
        provisionStatus = nil
    }

    private enum PreflightResult {
        case compatible
        case appTooOld(required: String)
        case serverTooOld(hops: [HopNodeProfile])
    }

    private func preflightVersions(hops: [HopNodeProfile]) async throws -> PreflightResult {
        let infos = try await ChainSSHForward.withChain(hops) { forward in
            var infos: [(hop: HopNodeProfile, info: ServerVersionInfo)] = []
            for index in hops.indices {
                let info = try await VersionService.fetchServerVersion(
                    on: forward.client(at: index),
                    hop: hops[index]
                )
                infos.append((hops[index], info))
            }
            return infos
        }

        for item in infos {
            if let minApp = item.info.minAppVersion,
               SemVer.compare(HopVersion.appVersion, minApp) == .orderedAscending {
                return .appTooOld(required: minApp)
            }
        }

        var outdated: [HopNodeProfile] = []
        for item in infos {
            guard let serverVersion = item.info.version else {
                continue
            }
            if SemVer.compare(serverVersion, HopVersion.manifest.minServerVersion) == .orderedAscending {
                outdated.append(item.hop)
            }
        }

        if !outdated.isEmpty {
            return .serverTooOld(hops: outdated)
        }
        return .compatible
    }

    func disconnect() {
        ignoreDisconnectError = true
        connectGeneration += 1
        provisionStatus = nil
        manager?.connection.stopVPNTunnel()
    }

    func toggle(restartHopperd: Bool = false) async {
        if isConnected || isBusy {
            disconnect()
        } else {
            await connect(restartHopperd: restartHopperd)
        }
    }

    private func persist() {
        ProfileStore.save(state)
    }

    private func ensureManager(hop: HopNodeProfile, context: TunnelConnectContext) async throws -> NETunnelProviderManager {
        let managers = try await NETunnelProviderManager.loadAllFromPreferences()
        let manager = managers.first(where: Self.isHopperManager) ?? NETunnelProviderManager()

        manager.localizedDescription = HopConstants.appDisplayName
        manager.isEnabled = true
        manager.protocolConfiguration = Self.makeProtocol(hop: hop, context: context)

        try await manager.saveToPreferences()
        try await manager.loadFromPreferences()

        guard manager.protocolConfiguration is NETunnelProviderProtocol else {
            throw VPNError.invalidProtocol
        }

        vpnStatus = manager.connection.status
        return manager
    }

    private func reloadVPN() async {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = managers.first(where: Self.isHopperManager)
            vpnStatus = manager?.connection.status ?? .invalid
            observeStatus()
        } catch {
            reportError(HopErrorDetails.describe(error))
        }
    }

    private func observeStatus() {
        if let statusObserver {
            NotificationCenter.default.removeObserver(statusObserver)
        }
        guard let connection = manager?.connection else { return }
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: connection,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.vpnStatus = self.manager?.connection.status ?? .invalid
                if self.vpnStatus == .disconnected {
                    await self.readTunnelError()
                }
            }
        }
    }

    private func readTunnelError() async {
        if ignoreDisconnectError {
            ignoreDisconnectError = false
            return
        }
        // Extension writes the app-group error slightly after NE flips to disconnected.
        try? await Task.sleep(nanoseconds: 500_000_000)
        if let saved = ProfileStore.loadLastTunnelError() {
            reportError(saved)
            return
        }
        guard let session = manager?.connection as? NETunnelProviderSession else { return }
        session.fetchLastDisconnectError { [weak self] error in
            Task { @MainActor in
                guard let self, let error else { return }
                if self.ignoreDisconnectError {
                    self.ignoreDisconnectError = false
                    return
                }
                self.reportError(HopErrorDetails.describe(error))
            }
        }
    }

    private func reportError(_ message: String) {
        let detail = HopErrorDetails.describeMessage(message)
        errorMessage = detail
        TunnelLog.error("[App] \(detail)")
    }

    private static func isHopperManager(_ manager: NETunnelProviderManager) -> Bool {
        (manager.protocolConfiguration as? NETunnelProviderProtocol)?
            .providerBundleIdentifier == HopConstants.tunnelBundleID
    }

    private static func makeProtocol(hop: HopNodeProfile, context: TunnelConnectContext) -> NETunnelProviderProtocol {
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = HopConstants.tunnelBundleID
        proto.serverAddress = hop.trimmedHost
        proto.providerConfiguration = TunnelBootstrap.options(hop: hop, context: context)
        return proto
    }
}

enum VPNError: LocalizedError {
    case invalidProtocol

    var errorDescription: String? {
        switch self {
        case .invalidProtocol:
            return "VPN protocol configuration is invalid. Reinstall the app and try again."
        }
    }
}

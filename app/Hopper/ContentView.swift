import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var vpn: VPNController
    @State private var showConnectOptions = false
    @State private var showServerUpdateAlert = false
    @State private var shareMode: HopperExportMode?
    @State private var showScanner = false
    @State private var importMode: HopperImportMode?

    private var actionsDisabled: Bool {
        vpn.isBusy || vpn.provisionStatus != nil
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Chain") {
                    if let chain = vpn.state.selectedChain {
                        Picker("Active chain", selection: Binding(
                            get: { vpn.state.selectedChainID },
                            set: { vpn.selectChain($0) }
                        )) {
                            ForEach(vpn.state.chains) { item in
                                Text(item.displayName).tag(Optional(item.id))
                            }
                        }

                        NavigationLink {
                            ChainDetailView(chainID: chain.id)
                        } label: {
                            Text(chainRouteSummary)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Text("No chain selected")
                            .foregroundStyle(.secondary)
                    }

                    NavigationLink {
                        ChainConfiguratorView()
                    } label: {
                        Label("Configure chains", systemImage: "link")
                    }
                }

                Section("Connect") {
                    if let entry = vpn.state.entryHop {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Entry: \(entry.displayName)").font(.headline)
                            Text("\(entry.trimmedUser)@\(entry.trimmedHost):\(entry.port)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Text("Add servers and build a chain (entry → exit).")
                            .foregroundStyle(.secondary)
                    }

                    // Bordered styles so List doesn't treat the whole row as one control
                    // (plain Buttons in an HStack often fire adjacent actions together).
                    VStack(spacing: 10) {
                        Text(statusLabel)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        if let provision = vpn.provisionStatus {
                            Text(provision)
                                .font(.footnote)
                                .foregroundStyle(.orange)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        if let error = vpn.errorMessage {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(.red)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        Button(vpn.isConnected ? "Disconnect" : "Connect") {
                            if vpn.isConnected || vpn.isBusy {
                                vpn.disconnect()
                            } else {
                                showConnectOptions = true
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                        .disabled(actionsDisabled || vpn.state.entryHop == nil)
                        .frame(maxWidth: .infinity)

                        HopperExportButtonsRow(
                            enabled: !actionsDisabled && !vpn.state.activeHops.isEmpty
                        ) { shareMode = $0 }

                        HopperImportButtons(enabled: !actionsDisabled) { importMode = $0 }

                        Button("Scan QR") { showScanner = true }
                            .buttonStyle(.bordered)
                            .disabled(actionsDisabled)
                            .frame(maxWidth: .infinity)
                    }
                }

                if let hops = optionalActiveHops, !hops.isEmpty {
                    Section("Route") {
                        ForEach(Array(hops.enumerated()), id: \.element.id) { index, hop in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(chainRole(index: index, total: hops.count, hop: hop))
                                    .font(.subheadline)
                                Text("\(hop.trimmedUser)@\(hop.trimmedHost):\(hop.port)")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("\(HopConstants.appDisplayName) \(HopConstants.appVersion)")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: vpn.serverUpdatePrompt != nil) { _, shouldShow in
                showServerUpdateAlert = shouldShow
            }
            .onChange(of: showServerUpdateAlert) { _, isShowing in
                if !isShowing, vpn.serverUpdatePrompt != nil {
                    Task { @MainActor in
                        vpn.cancelServerUpdate()
                    }
                }
            }
            .alert("Update servers?", isPresented: $showServerUpdateAlert) {
                if let prompt = vpn.serverUpdatePrompt {
                    Button("Update \(prompt.hops.count) hop(s)") {
                        Task { await vpn.confirmServerUpdate() }
                    }
                    Button("Cancel", role: .cancel) {}
                }
            } message: {
                if let prompt = vpn.serverUpdatePrompt {
                    Text("Server software is older than app v\(prompt.targetVersion). Update before connecting?")
                }
            }
            .confirmationDialog(
                "Connect to chain",
                isPresented: $showConnectOptions,
                titleVisibility: .visible
            ) {
                Button("Connect") {
                    scheduleConnect(restartHopperd: false)
                }
                Button("Connect & restart hopperd") {
                    scheduleConnect(restartHopperd: true)
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Restart hopperd on all nodes if you've changed the chain or are having connection issues on the servers. Leave off for faster reconnects.")
            }
            .onChange(of: vpn.chainImportPrompt != nil) { _, showing in
                if showing {
                    importMode = nil
                    showScanner = false
                    shareMode = nil
                    showConnectOptions = false
                }
            }
            .alert(
                "Chain imported",
                isPresented: Binding(
                    get: { vpn.chainImportPrompt != nil },
                    set: { if !$0 { vpn.dismissChainImportPrompt() } }
                )
            ) {
                // New chain ID: normal Connect starts hopperd if needed (--if-running skip),
                // without stopping other chains' processes.
                Button("Connect") {
                    vpn.dismissChainImportPrompt()
                    scheduleConnect(restartHopperd: false)
                }
                Button("Close", role: .cancel) {
                    vpn.dismissChainImportPrompt()
                }
            } message: {
                Text(vpn.chainImportPrompt?.message ?? "The chain was imported successfully.")
            }
            .sheet(item: $shareMode) { mode in
                ChainExportView(
                    chainName: vpn.state.selectedChain?.name ?? "",
                    hops: vpn.state.activeHops,
                    mode: mode
                )
            }
            .sheet(isPresented: $showScanner) {
                QRCodeScannerView { payload in
                    if vpn.handleScannedQR(payload) {
                        showScanner = false
                    }
                }
            }
            .sheet(item: $importMode) { mode in
                HopperImportSheet(mode: mode) { importMode = nil }
            }
            .onChange(of: vpn.pendingLanInvite) { _, invite in
                if invite != nil { showScanner = false }
            }
            .sheet(item: Binding(
                get: { vpn.pendingHopperConfData.map { HopperConfPendingItem(data: $0) } },
                set: { if $0 == nil { vpn.pendingHopperConfData = nil } }
            )) { item in
                HopperConfOpenPasswordView(
                    fileData: item.data,
                    onImport: { payload in
                        _ = vpn.importPayload(payload)
                        vpn.pendingHopperConfData = nil
                    },
                    onCancel: {
                        vpn.pendingHopperConfData = nil
                    }
                )
            }
        }
    }

    /// Dismiss overlays first and delay connect so alert/dialog dismissal
    /// doesn't deliver the same tap to export buttons underneath (which races VPN start).
    private func scheduleConnect(restartHopperd: Bool) {
        shareMode = nil
        showScanner = false
        importMode = nil
        showConnectOptions = false
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 450_000_000)
            await vpn.connect(restartHopperd: restartHopperd)
        }
    }

    private var optionalActiveHops: [HopNodeProfile]? {
        let hops = vpn.state.activeHops
        return hops.isEmpty ? nil : hops
    }

    private var chainRouteSummary: String {
        let hops = vpn.state.activeHops
        if hops.isEmpty { return "No servers in chain" }
        if hops.count == 1 { return hops[0].displayName }
        return "\(hops.first!.displayName) → \(hops.last!.displayName) (\(hops.count) hops)"
    }

    private func chainRole(index: Int, total: Int, hop: HopNodeProfile) -> String {
        let role: String
        if total == 1 {
            role = "Exit"
        } else if index == 0 {
            role = "Entry"
        } else if index == total - 1 {
            role = "Exit"
        } else {
            role = "Relay"
        }
        return "\(index + 1). \(role) — \(hop.displayName)"
    }

    private var statusLabel: String {
        switch vpn.vpnStatus {
        case .connected: return "Connected"
        case .connecting: return "Connecting…"
        case .disconnecting: return "Disconnecting…"
        case .reasserting: return "Reconnecting…"
        case .disconnected: return "Disconnected"
        default: return "Not configured"
        }
    }
}

private struct HopperConfPendingItem: Identifiable {
    let id: Int
    let data: Data

    init(data: Data) {
        self.data = data
        self.id = data.hashValue
    }
}

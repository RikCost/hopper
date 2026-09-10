import Foundation

struct AppState: Codable, Equatable {
    var servers: [HopNodeProfile] = []
    var chains: [HopChain] = []
    var selectedChainID: UUID?
    var deployKeys: [DeploySSHKey] = []

    enum CodingKeys: String, CodingKey {
        case servers, chains, selectedChainID, deployKeys
    }

    init(
        servers: [HopNodeProfile] = [],
        chains: [HopChain] = [],
        selectedChainID: UUID? = nil,
        deployKeys: [DeploySSHKey] = []
    ) {
        self.servers = servers
        self.chains = chains
        self.selectedChainID = selectedChainID
        self.deployKeys = deployKeys
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        servers = try container.decodeIfPresent([HopNodeProfile].self, forKey: .servers) ?? []
        chains = try container.decodeIfPresent([HopChain].self, forKey: .chains) ?? []
        selectedChainID = try container.decodeIfPresent(UUID.self, forKey: .selectedChainID)
        deployKeys = try container.decodeIfPresent([DeploySSHKey].self, forKey: .deployKeys) ?? []
    }

    var selectedChain: HopChain? {
        guard let selectedChainID else { return nil }
        return chains.first { $0.id == selectedChainID }
    }

    /// Resolved hops for the selected chain (entry → exit).
    var activeHops: [HopNodeProfile] {
        guard let chain = selectedChain else { return [] }
        return resolveHops(chain)
    }

    var entryHop: HopNodeProfile? { activeHops.first }

    func server(id: UUID) -> HopNodeProfile? {
        servers.first { $0.id == id }
    }

    func resolveHops(_ chain: HopChain) -> [HopNodeProfile] {
        chain.hopIDs.compactMap { server(id: $0) }
    }

    mutating func addServer(_ server: HopNodeProfile) {
        servers.append(server)
    }

    mutating func addDeployKey(_ key: DeploySSHKey) {
        deployKeys.append(key)
    }

    /// Adds a key unless an existing library entry has the same public-key fingerprint.
    @discardableResult
    mutating func importDeployKey(_ key: DeploySSHKey) -> DeploySSHKey {
        if let fingerprint = KeysLibraryMigration.fingerprint(privateKeyPEM: key.privateKey),
           let existing = deployKeys.first(where: {
               KeysLibraryMigration.fingerprint(privateKeyPEM: $0.privateKey) == fingerprint
           }) {
            return existing
        }
        var stored = key
        stored.id = UUID()
        if stored.createdAt.timeIntervalSince1970 == 0 {
            stored.createdAt = Date()
        }
        deployKeys.append(stored)
        return stored
    }

    mutating func renameDeployKey(id: UUID, name: String) {
        guard let index = deployKeys.firstIndex(where: { $0.id == id }) else { return }
        deployKeys[index].name = name
    }

    mutating func recordDeployKeyUse(id: UUID, server: HopNodeProfile) {
        guard let index = deployKeys.firstIndex(where: { $0.id == id }) else { return }
        deployKeys[index].recordAssignment(from: server)
    }

    mutating func removeDeployKeys(at offsets: IndexSet) {
        for index in offsets.sorted(by: >) where deployKeys.indices.contains(index) {
            guard deployKeys[index].canDelete(servers: servers) else { continue }
            deployKeys.remove(at: index)
        }
    }

    mutating func removeDeployKey(id: UUID) {
        guard let key = deployKey(id: id), key.canDelete(servers: servers) else { return }
        deployKeys.removeAll { $0.id == id }
    }

    func deployKey(id: UUID) -> DeploySSHKey? {
        deployKeys.first { $0.id == id }
    }

    mutating func renameServer(id: UUID, name: String) {
        guard let index = servers.firstIndex(where: { $0.id == id }) else { return }
        servers[index].name = name
    }

    mutating func removeServers(at offsets: IndexSet) {
        let removed = offsets.compactMap { servers.indices.contains($0) ? servers[$0].id : nil }
        for index in offsets.sorted(by: >) where servers.indices.contains(index) {
            servers.remove(at: index)
        }
        for i in chains.indices {
            chains[i].hopIDs.removeAll { removed.contains($0) }
        }
        if let selected = selectedChainID, !chains.contains(where: { $0.id == selected }) {
            selectedChainID = chains.first?.id
        }
    }

    mutating func addChain(name: String = "") -> UUID {
        let chain = HopChain(name: name)
        chains.append(chain)
        if selectedChainID == nil {
            selectedChainID = chain.id
        }
        return chain.id
    }

    mutating func removeChains(at offsets: IndexSet) {
        let removed = offsets.compactMap { chains.indices.contains($0) ? chains[$0].id : nil }
        for index in offsets.sorted(by: >) where chains.indices.contains(index) {
            chains.remove(at: index)
        }
        if let selected = selectedChainID, removed.contains(selected) {
            selectedChainID = chains.first?.id
        }
    }

    mutating func selectChain(_ id: UUID?) {
        selectedChainID = id
    }

    mutating func renameChain(id: UUID, name: String) {
        guard let index = chains.firstIndex(where: { $0.id == id }) else { return }
        chains[index].name = name
    }

    mutating func moveHopInChain(chainID: UUID, from source: IndexSet, to destination: Int) {
        guard let index = chains.firstIndex(where: { $0.id == chainID }) else { return }
        var hopIDs = chains[index].hopIDs
        let moving = source.sorted().map { hopIDs[$0] }
        var target = destination
        for offset in source.sorted() where offset < destination {
            target -= 1
        }
        for offset in source.sorted(by: >) {
            hopIDs.remove(at: offset)
        }
        for (i, id) in moving.enumerated() {
            hopIDs.insert(id, at: target + i)
        }
        chains[index].hopIDs = hopIDs
    }

    mutating func addServerToChain(chainID: UUID, serverID: UUID) {
        guard let index = chains.firstIndex(where: { $0.id == chainID }) else { return }
        guard servers.contains(where: { $0.id == serverID }) else { return }
        guard !chains[index].hopIDs.contains(serverID) else { return }
        chains[index].hopIDs.append(serverID)
    }

    mutating func removeHopFromChain(chainID: UUID, at offsets: IndexSet) {
        guard let index = chains.firstIndex(where: { $0.id == chainID }) else { return }
        for offset in offsets.sorted(by: >) where chains[index].hopIDs.indices.contains(offset) {
            chains[index].hopIDs.remove(at: offset)
        }
    }

    /// Migrate flat hop list from older app versions.
    static func fromLegacyHops(_ hops: [HopNodeProfile]) -> AppState {
        var state = AppState(servers: hops)
        if hops.isEmpty { return state }
        let chainID = state.addChain(name: "Default")
        if let chainIndex = state.chains.firstIndex(where: { $0.id == chainID }) {
            state.chains[chainIndex].hopIDs = hops.map(\.id)
        }
        state.selectedChainID = chainID
        return state
    }
}

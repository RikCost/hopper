import Foundation

enum ProfileStore {
    private static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: HopConstants.appGroupID)
    }

    private static var storeURL: URL? {
        containerURL?.appendingPathComponent(HopConstants.profileStoreFileName)
    }

    static func load() -> AppState {
        guard let storeURL else {
            TunnelLog.error("App group unavailable — check entitlements for \(HopConstants.appGroupID)")
            return AppState()
        }
        guard FileManager.default.fileExists(atPath: storeURL.path) else {
            TunnelLog.info("No saved profile yet at \(storeURL.path)")
            return AppState()
        }
        guard let data = try? Data(contentsOf: storeURL) else {
            TunnelLog.error("Could not read profile at \(storeURL.path)")
            return AppState()
        }
        if var state = try? JSONDecoder().decode(AppState.self, from: data) {
            var shouldSave = false
            if state.chains.isEmpty, !state.servers.isEmpty {
                let id = state.addChain(name: "Default")
                if let index = state.chains.firstIndex(where: { $0.id == id }) {
                    state.chains[index].hopIDs = state.servers.map(\.id)
                }
                state.selectedChainID = id
                shouldSave = true
            }
            let migrated = KeysLibraryMigration.migrate(state)
            if migrated != state {
                state = migrated
                shouldSave = true
            }
            if shouldSave {
                save(state)
            }
            return state
        }
        if let flat = try? JSONDecoder().decode(FlatHopsState.self, from: data) {
            TunnelLog.info("Migrated flat hop list to chains")
            return AppState.fromLegacyHops(flat.hops)
        }
        if let legacy = try? JSONDecoder().decode(LegacyHopLibrary.self, from: data) {
            TunnelLog.info("Migrated legacy hop library")
            return legacy.migrated()
        }
        TunnelLog.error("Profile decode failed at \(storeURL.path)")
        return AppState()
    }

    static func save(_ state: AppState) {
        guard let storeURL else {
            TunnelLog.error("App group save failed — entitlement \(HopConstants.appGroupID) missing?")
            return
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(state) else { return }
        do {
            try data.write(to: storeURL, options: .atomic)
        } catch {
            TunnelLog.error("Profile write failed: \(error.localizedDescription)")
        }
    }

    static func deviceID() -> UUID {
        guard let containerURL else { return UUID() }
        let url = containerURL.appendingPathComponent(HopConstants.deviceIDKey)
        if let text = try? String(contentsOf: url, encoding: .utf8),
           let existing = UUID(uuidString: text.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return existing
        }
        let fresh = UUID()
        try? fresh.uuidString.write(to: url, atomically: true, encoding: .utf8)
        return fresh
    }

    private static var lastErrorURL: URL? {
        containerURL?.appendingPathComponent(HopConstants.tunnelLastErrorFileName)
    }

    static func saveLastTunnelError(_ message: String) {
        guard let lastErrorURL else { return }
        try? message.write(to: lastErrorURL, atomically: true, encoding: .utf8)
    }

    static func loadLastTunnelError() -> String? {
        guard let lastErrorURL,
              let text = try? String(contentsOf: lastErrorURL, encoding: .utf8)
        else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    static func clearLastTunnelError() {
        guard let lastErrorURL else { return }
        try? FileManager.default.removeItem(at: lastErrorURL)
    }
}

// MARK: - Legacy format migration

private struct FlatHopsState: Codable {
    var hops: [HopNodeProfile]
    var selectedHopID: UUID?
}

private struct LegacyHopLibrary: Codable {
    var nodes: [HopNodeProfile]
    var chains: [LegacyChain]
    var selectedChainID: UUID?

    struct LegacyChain: Codable {
        var id: UUID?
        var name: String?
        var hopIDs: [UUID]
    }

    func migrated() -> AppState {
        var state = AppState(servers: nodes)
        if chains.isEmpty, !nodes.isEmpty {
            return AppState.fromLegacyHops(nodes)
        }
        state.chains = chains.map {
            HopChain(
                id: $0.id ?? UUID(),
                name: $0.name ?? "",
                hopIDs: $0.hopIDs
            )
        }
        state.selectedChainID = selectedChainID ?? state.chains.first?.id
        return state
    }
}

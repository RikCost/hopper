import Foundation

enum KeysLibraryMigration {
    /// Deduplicate by public-key fingerprint and seed assignments from
    /// auto-generated `Deploy user@host` names. Does not import hop daemon keys.
    static func migrate(_ state: AppState) -> AppState {
        var next = state
        next.deployKeys = deduplicated(next.deployKeys)
        for index in next.deployKeys.indices {
            next.deployKeys[index].assignments = seedAssignments(
                key: next.deployKeys[index],
                servers: next.servers
            )
        }
        return next
    }

    static func fingerprint(privateKeyPEM: String) -> String? {
        SSHKeyGenerator.normalizedPublicKeyLine(privateKeyPEM: privateKeyPEM)
    }

    static func parseDeployName(_ name: String) -> (user: String, host: String)? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = "deploy "
        guard trimmed.lowercased().hasPrefix(prefix) else { return nil }
        let rest = String(trimmed.dropFirst(prefix.count))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let at = rest.lastIndex(of: "@") else { return nil }
        let user = String(rest[..<at]).trimmingCharacters(in: .whitespacesAndNewlines)
        let host = String(rest[rest.index(after: at)...])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !user.isEmpty, !host.isEmpty else { return nil }
        return (user, host)
    }

    private static func deduplicated(_ keys: [DeploySSHKey]) -> [DeploySSHKey] {
        var kept: [DeploySSHKey] = []
        var indexByFingerprint: [String: Int] = [:]

        for key in keys {
            guard let fingerprint = fingerprint(privateKeyPEM: key.privateKey) else {
                kept.append(key)
                continue
            }
            if let existingIndex = indexByFingerprint[fingerprint] {
                kept[existingIndex] = merge(kept[existingIndex], key)
            } else {
                indexByFingerprint[fingerprint] = kept.count
                kept.append(key)
            }
        }
        return kept
    }

    private static func merge(_ primary: DeploySSHKey, _ extra: DeploySSHKey) -> DeploySSHKey {
        var merged = primary
        if merged.trimmedName.isEmpty, !extra.trimmedName.isEmpty {
            merged.name = extra.name
        }
        if extra.createdAt < merged.createdAt {
            merged.createdAt = extra.createdAt
        }
        for assignment in extra.assignments {
            if let index = merged.assignments.firstIndex(where: { $0.sameTarget(as: assignment) }) {
                if merged.assignments[index].serverID == nil {
                    merged.assignments[index].serverID = assignment.serverID
                }
            } else {
                merged.assignments.append(assignment)
            }
        }
        return merged
    }

    private static func seedAssignments(key: DeploySSHKey, servers: [HopNodeProfile]) -> [DeployKeyAssignment] {
        var assignments = key.assignments
        guard let parsed = parseDeployName(key.name) else { return assignments }

        let matches = servers.filter {
            $0.trimmedHost.caseInsensitiveCompare(parsed.host) == .orderedSame
                && $0.trimmedUser.caseInsensitiveCompare(parsed.user) == .orderedSame
        }
        for server in matches {
            let next = DeployKeyAssignment(
                serverID: server.id,
                host: server.trimmedHost,
                user: server.trimmedUser,
                port: server.port
            )
            if let index = assignments.firstIndex(where: { $0.sameTarget(as: next) }) {
                if assignments[index].serverID == nil {
                    assignments[index].serverID = server.id
                }
            } else {
                assignments.append(next)
            }
        }
        return assignments
    }
}

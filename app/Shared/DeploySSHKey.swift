import Foundation

struct DeployKeyAssignment: Codable, Equatable, Hashable, Identifiable {
    var id: UUID = UUID()
    var serverID: UUID?
    var host: String = ""
    var user: String = ""
    var port: Int = HopConstants.defaultSSHPort

    var userAtHost: String {
        let userPart = user.trimmingCharacters(in: .whitespacesAndNewlines)
        let hostPart = host.trimmingCharacters(in: .whitespacesAndNewlines)
        if userPart.isEmpty { return hostPart }
        if hostPart.isEmpty { return userPart }
        return "\(userPart)@\(hostPart)"
    }

    func sameTarget(as other: DeployKeyAssignment) -> Bool {
        user.caseInsensitiveCompare(other.user) == .orderedSame
            && host.caseInsensitiveCompare(other.host) == .orderedSame
            && port == other.port
    }

    func label(servers: [HopNodeProfile]) -> String {
        let target = userAtHost
        if let serverID,
           let server = servers.first(where: { $0.id == serverID }) {
            return "\(server.displayName) — \(target)"
        }
        if let server = servers.first(where: {
            $0.trimmedHost.caseInsensitiveCompare(host) == .orderedSame
                && $0.trimmedUser.caseInsensitiveCompare(user) == .orderedSame
        }) {
            return "\(server.displayName) — \(target)"
        }
        return target
    }
}

struct DeploySSHKey: Codable, Equatable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String = ""
    var privateKey: String = ""
    var createdAt: Date = Date()
    var assignments: [DeployKeyAssignment] = []

    var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var displayName: String {
        trimmedName.isEmpty ? "Untitled key" : trimmedName
    }

    var publicKeyLine: String? {
        try? SSHKeyGenerator.publicKeyLine(privateKeyPEM: privateKey, comment: trimmedName)
    }

    mutating func recordAssignment(from server: HopNodeProfile) {
        let next = DeployKeyAssignment(
            serverID: server.id,
            host: server.trimmedHost,
            user: server.trimmedUser,
            port: server.port
        )
        if let index = assignments.firstIndex(where: { $0.sameTarget(as: next) }) {
            assignments[index].serverID = server.id
            assignments[index].host = next.host
            assignments[index].user = next.user
            assignments[index].port = next.port
        } else {
            assignments.append(next)
        }
    }

    func assignedServers(in servers: [HopNodeProfile]) -> [HopNodeProfile] {
        servers.filter { server in
            assignments.contains { assignment in
                if let serverID = assignment.serverID, serverID == server.id {
                    return true
                }
                return !assignment.host.isEmpty
                    && server.trimmedHost.caseInsensitiveCompare(assignment.host) == .orderedSame
                    && server.trimmedUser.caseInsensitiveCompare(assignment.user) == .orderedSame
            }
        }
    }

    func canDelete(servers: [HopNodeProfile]) -> Bool {
        assignedServers(in: servers).isEmpty
    }

    enum CodingKeys: String, CodingKey {
        case id, name, privateKey, createdAt, assignments
    }

    init(
        id: UUID = UUID(),
        name: String = "",
        privateKey: String = "",
        createdAt: Date = Date(),
        assignments: [DeployKeyAssignment] = []
    ) {
        self.id = id
        self.name = name
        self.privateKey = privateKey
        self.createdAt = createdAt
        self.assignments = assignments
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        privateKey = try container.decodeIfPresent(String.self, forKey: .privateKey) ?? ""
        createdAt = try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
        assignments = try container.decodeIfPresent([DeployKeyAssignment].self, forKey: .assignments) ?? []
    }
}

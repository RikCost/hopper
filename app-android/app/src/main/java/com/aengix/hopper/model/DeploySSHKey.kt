package com.aengix.hopper.model

import kotlinx.serialization.Serializable

@Serializable
data class DeployKeyAssignment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val serverID: String? = null,
    val host: String = "",
    val user: String = "",
    val port: Int = HopConstants.DEFAULT_SSH_PORT,
) {
    val userAtHost: String
        get() {
            val userPart = user.trim()
            val hostPart = host.trim()
            return when {
                userPart.isEmpty() -> hostPart
                hostPart.isEmpty() -> userPart
                else -> "$userPart@$hostPart"
            }
        }

    fun sameTarget(other: DeployKeyAssignment): Boolean =
        user.equals(other.user, ignoreCase = true) &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port

    fun label(servers: List<HopNodeProfile>): String {
        val target = userAtHost
        serverID?.let { id ->
            servers.firstOrNull { it.id == id }?.let { return "${it.displayName} — $target" }
        }
        servers.firstOrNull {
            it.trimmedHost.equals(host, ignoreCase = true) &&
                it.trimmedUser.equals(user, ignoreCase = true)
        }?.let { return "${it.displayName} — $target" }
        return target
    }
}

@Serializable
data class DeploySSHKey(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val privateKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val assignments: List<DeployKeyAssignment> = emptyList(),
) {
    val trimmedName: String get() = name.trim()
    val displayName: String get() = trimmedName.ifEmpty { "Untitled key" }

    fun recordAssignment(server: HopNodeProfile): DeploySSHKey {
        val next = DeployKeyAssignment(
            serverID = server.id,
            host = server.trimmedHost,
            user = server.trimmedUser,
            port = server.port,
        )
        val existingIndex = assignments.indexOfFirst { it.sameTarget(next) }
        val updated = if (existingIndex >= 0) {
            assignments.toMutableList().also { list ->
                list[existingIndex] = list[existingIndex].copy(
                    serverID = server.id,
                    host = next.host,
                    user = next.user,
                    port = next.port,
                )
            }
        } else {
            assignments + next
        }
        return copy(assignments = updated)
    }

    fun assignedServers(servers: List<HopNodeProfile>): List<HopNodeProfile> =
        servers.filter { server ->
            assignments.any { assignment ->
                assignment.serverID == server.id || (
                    assignment.host.isNotBlank() &&
                        server.trimmedHost.equals(assignment.host, ignoreCase = true) &&
                        server.trimmedUser.equals(assignment.user, ignoreCase = true)
                )
            }
        }

    fun canDelete(servers: List<HopNodeProfile>): Boolean =
        assignedServers(servers).isEmpty()
}

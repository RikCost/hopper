package com.aengix.hopper.data

import com.aengix.hopper.model.AppState
import com.aengix.hopper.model.DeployKeyAssignment
import com.aengix.hopper.model.DeploySSHKey
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.ssh.SSHKeyGenerator

object KeysLibraryMigration {
    /** Deduplicate by public-key fingerprint and seed assignments from `Deploy user@host` names. */
    fun migrate(state: AppState): AppState {
        var keys = deduplicated(state.deployKeys)
        keys = keys.map { key ->
            val seeded = seedAssignments(key, state.servers)
            if (seeded == key.assignments) key else key.copy(assignments = seeded)
        }
        return if (keys == state.deployKeys) state else state.copy(deployKeys = keys)
    }

    fun fingerprint(privateKeyPem: String): String? =
        SSHKeyGenerator.normalizedPublicKeyLine(privateKeyPem)

    fun parseDeployName(name: String): Pair<String, String>? {
        val trimmed = name.trim()
        val prefix = "deploy "
        if (!trimmed.lowercase().startsWith(prefix)) return null
        val rest = trimmed.substring(prefix.length).trim()
        val at = rest.lastIndexOf('@')
        if (at <= 0 || at == rest.lastIndex) return null
        val user = rest.substring(0, at).trim()
        val host = rest.substring(at + 1).trim()
        if (user.isEmpty() || host.isEmpty()) return null
        return user to host
    }

    private fun deduplicated(keys: List<DeploySSHKey>): List<DeploySSHKey> {
        val kept = mutableListOf<DeploySSHKey>()
        val indexByFingerprint = mutableMapOf<String, Int>()
        for (key in keys) {
            val fingerprint = fingerprint(key.privateKey)
            if (fingerprint == null) {
                kept += key
                continue
            }
            val existingIndex = indexByFingerprint[fingerprint]
            if (existingIndex != null) {
                kept[existingIndex] = merge(kept[existingIndex], key)
            } else {
                indexByFingerprint[fingerprint] = kept.size
                kept += key
            }
        }
        return kept
    }

    private fun merge(primary: DeploySSHKey, extra: DeploySSHKey): DeploySSHKey {
        var merged = primary
        if (merged.trimmedName.isEmpty() && extra.trimmedName.isNotEmpty()) {
            merged = merged.copy(name = extra.name)
        }
        if (extra.createdAt < merged.createdAt) {
            merged = merged.copy(createdAt = extra.createdAt)
        }
        var assignments = merged.assignments
        for (assignment in extra.assignments) {
            val index = assignments.indexOfFirst { it.sameTarget(assignment) }
            assignments = if (index >= 0) {
                val current = assignments[index]
                if (current.serverID == null && assignment.serverID != null) {
                    assignments.toMutableList().also { it[index] = current.copy(serverID = assignment.serverID) }
                } else {
                    assignments
                }
            } else {
                assignments + assignment
            }
        }
        return merged.copy(assignments = assignments)
    }

    private fun seedAssignments(key: DeploySSHKey, servers: List<HopNodeProfile>): List<DeployKeyAssignment> {
        val parsed = parseDeployName(key.name) ?: return key.assignments
        var assignments = key.assignments
        val matches = servers.filter {
            it.trimmedHost.equals(parsed.second, ignoreCase = true) &&
                it.trimmedUser.equals(parsed.first, ignoreCase = true)
        }
        for (server in matches) {
            val next = DeployKeyAssignment(
                serverID = server.id,
                host = server.trimmedHost,
                user = server.trimmedUser,
                port = server.port,
            )
            val index = assignments.indexOfFirst { it.sameTarget(next) }
            assignments = if (index >= 0) {
                val current = assignments[index]
                if (current.serverID == null) {
                    assignments.toMutableList().also { it[index] = current.copy(serverID = server.id) }
                } else {
                    assignments
                }
            } else {
                assignments + next
            }
        }
        return assignments
    }
}

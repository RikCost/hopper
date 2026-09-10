package com.aengix.hopper.data

import android.content.Context
import com.aengix.hopper.model.AppState
import com.aengix.hopper.model.HopChain
import com.aengix.hopper.model.HopConstants
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.util.TunnelLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object ProfileStore {
    private lateinit var appContext: Context

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val storeFile: File
        get() = File(appContext.filesDir, HopConstants.PROFILE_STORE_FILE_NAME)

    private val lastErrorFile: File
        get() = File(appContext.filesDir, HopConstants.TUNNEL_LAST_ERROR_FILE_NAME)

    fun load(): AppState {
        val file = storeFile
        if (!file.exists()) {
            TunnelLog.info("No saved profile yet at ${file.path}")
            return AppState()
        }

        val text = runCatching { file.readText() }.getOrElse {
            TunnelLog.error("Could not read profile at ${file.path}")
            return AppState()
        }

        runCatching { json.decodeFromString<AppState>(text) }.onSuccess { decoded ->
            var state = decoded
            var shouldSave = false
            if (state.chains.isEmpty() && state.servers.isNotEmpty()) {
                state = AppState.fromLegacyHops(state.servers)
                shouldSave = true
            }
            val migrated = KeysLibraryMigration.migrate(state)
            if (migrated != state) {
                state = migrated
                shouldSave = true
            }
            if (shouldSave) save(state)
            return state
        }

        runCatching { json.decodeFromString<FlatHopsState>(text) }.onSuccess { flat ->
            TunnelLog.info("Migrated flat hop list to chains")
            return AppState.fromLegacyHops(flat.hops)
        }

        runCatching { json.decodeFromString<LegacyHopLibrary>(text) }.onSuccess { legacy ->
            TunnelLog.info("Migrated legacy hop library")
            return legacy.migrated()
        }

        TunnelLog.error("Profile decode failed at ${file.path}")
        return AppState()
    }

    fun save(state: AppState) {
        runCatching {
            storeFile.writeText(json.encodeToString(state))
        }.onFailure {
            TunnelLog.error("Profile write failed: ${it.message}")
        }
    }

    fun saveLastTunnelError(message: String) {
        runCatching { lastErrorFile.writeText(message) }
    }

    fun loadLastTunnelError(): String? {
        if (!lastErrorFile.exists()) return null
        return lastErrorFile.readText().trim().ifEmpty { null }
    }

    fun clearLastTunnelError() {
        runCatching { lastErrorFile.delete() }
    }

    fun deviceId(): String {
        val file = File(appContext.filesDir, HopConstants.DEVICE_ID_KEY)
        if (file.exists()) {
            file.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        val fresh = java.util.UUID.randomUUID().toString()
        file.writeText(fresh)
        return fresh
    }
}

@Serializable
private data class FlatHopsState(
    val hops: List<HopNodeProfile>,
    val selectedHopID: String? = null,
)

@Serializable
private data class LegacyHopLibrary(
    val nodes: List<HopNodeProfile>,
    val chains: List<LegacyChain> = emptyList(),
    val selectedChainID: String? = null,
) {
    @Serializable
    data class LegacyChain(
        val id: String? = null,
        val name: String? = null,
        val hopIDs: List<String> = emptyList(),
    )

    fun migrated(): AppState {
        if (chains.isEmpty() && nodes.isNotEmpty()) {
            return AppState.fromLegacyHops(nodes)
        }
        val mappedChains = chains.map { chain ->
            HopChain(
                id = chain.id ?: java.util.UUID.randomUUID().toString(),
                name = chain.name.orEmpty(),
                hopIDs = chain.hopIDs,
            )
        }
        return AppState(
            servers = nodes,
            chains = mappedChains,
            selectedChainID = selectedChainID ?: mappedChains.firstOrNull()?.id,
        )
    }
}

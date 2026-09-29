package com.aengix.hopper.vpn

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aengix.hopper.data.DemoProfiles
import com.aengix.hopper.data.HopperConf
import com.aengix.hopper.data.HopperLanInvite
import com.aengix.hopper.data.ProfileStore
import com.aengix.hopper.model.AppState
import com.aengix.hopper.model.ChainStatusReport
import com.aengix.hopper.model.ChainTopology
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.model.HopVersion
import com.aengix.hopper.model.SemVer
import com.aengix.hopper.model.ServerUpdatePrompt
import com.aengix.hopper.model.TunnelConnectContext
import com.aengix.hopper.provision.ChainProvisioner
import com.aengix.hopper.provision.VersionService
import com.aengix.hopper.util.HopErrorDetails
import com.aengix.hopper.util.TunnelLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class VpnController(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ProfileStore.load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    var onVpnPermissionRequired: ((Intent) -> Unit)? = null

    private val _vpnStatus = MutableStateFlow(VpnStatus.Disconnected)
    val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()

    private val _provisionStatus = MutableStateFlow<String?>(null)
    val provisionStatus: StateFlow<String?> = _provisionStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _serverUpdatePrompt = MutableStateFlow<ServerUpdatePrompt?>(null)
    val serverUpdatePrompt: StateFlow<ServerUpdatePrompt?> = _serverUpdatePrompt.asStateFlow()

    private val _chainStatusReports = MutableStateFlow<Map<String, List<ChainStatusReport>>>(emptyMap())
    val chainStatusReports: StateFlow<Map<String, List<ChainStatusReport>>> = _chainStatusReports.asStateFlow()

    private var pendingConnectRestart = false

    private val statusListener: (VpnStatus, String?) -> Unit = { status, error ->
        _vpnStatus.value = status
        if (error != null) {
            _errorMessage.value = HopErrorDetails.describeMessage(error)
        }
    }

    init {
        VpnStatusBus.addListener(statusListener)
    }

    val isConnected: Boolean get() = _vpnStatus.value == VpnStatus.Connected
    val isBusy: Boolean
        get() = _vpnStatus.value == VpnStatus.Connecting ||
            _vpnStatus.value == VpnStatus.Disconnecting

    fun addServer(server: HopNodeProfile) {
        updateState { it.addServer(server) }
    }

    fun deployServer(
        host: String,
        port: Int,
        user: String,
        auth: com.aengix.hopper.provision.ServerDeployAuth,
        onLog: ((String) -> Unit)? = null,
    ) {
        val result = com.aengix.hopper.provision.ServerDeployer.deploy(
            host = host,
            port = port,
            user = user,
            auth = auth,
            onLog = onLog,
        )
        updateState { state ->
            var next = state
            result.newDeployKey?.let { key ->
                next = next.addDeployKey(key.recordAssignment(result.profile))
            } ?: run {
                val usedId = (auth as? com.aengix.hopper.provision.ServerDeployAuth.DeployKey)?.key?.id
                if (usedId != null) {
                    next = next.recordDeployKeyUse(usedId, result.profile)
                }
            }
            next.addServer(result.profile)
        }
    }

    fun addDeployKey(key: com.aengix.hopper.model.DeploySSHKey) {
        updateState { it.addDeployKey(key) }
    }

    fun recordDeployKeyUse(id: String, server: HopNodeProfile) {
        updateState { it.recordDeployKeyUse(id, server) }
    }

    fun importDeployKey(key: com.aengix.hopper.model.DeploySSHKey) {
        updateState { it.importDeployKey(key) }
    }

    fun renameDeployKey(id: String, name: String) {
        updateState { it.renameDeployKey(id, name) }
    }

    fun deleteDeployKeys(ids: Set<String>) {
        updateState { it.removeDeployKeys(ids) }
    }

    fun deleteServers(ids: Set<String>) {
        updateState { it.removeServers(ids) }
    }

    fun renameServer(id: String, name: String) {
        updateState { it.renameServer(id, name) }
    }

    fun addChain(name: String = ""): String {
        var chainId = ""
        updateState {
            val (next, id) = it.addChain(name)
            chainId = id
            next
        }
        return chainId
    }

    fun deleteChains(ids: Set<String>) {
        updateState { it.removeChains(ids) }
    }

    fun selectChain(id: String?) {
        updateState { it.selectChain(id) }
    }

    fun loadDemoData() {
        updateState { DemoProfiles.appState() }
    }

    fun renameChain(id: String, name: String) {
        updateState { it.renameChain(id, name) }
    }

    fun moveHopInChain(chainID: String, fromIndex: Int, toIndex: Int) {
        updateState { it.moveHopInChain(chainID, fromIndex, toIndex) }
    }

    fun addServerToChain(chainID: String, serverID: String) {
        updateState { it.addServerToChain(chainID, serverID) }
    }

    fun removeHopFromChain(chainID: String, indices: Set<Int>) {
        updateState { it.removeHopFromChain(chainID, indices) }
    }

    /** Apply a shared server or chain payload (new IDs minted for servers/chains). */
    fun importPayload(
        payload: com.aengix.hopper.data.HopperConf.Payload,
        announceChain: Boolean = true,
    ): String {
        var message = ""
        var importedChainId: String? = null
        updateState { state ->
            var next = state
            when (payload) {
                is com.aengix.hopper.data.HopperConf.Payload.Server -> {
                    message = "Imported server ${payload.profile.displayName}."
                    next.addServer(payload.profile)
                }
                is com.aengix.hopper.data.HopperConf.Payload.Chain -> {
                    val hopIDs = mutableListOf<String>()
                    for (hop in payload.hops) {
                        next = next.addServer(hop)
                        hopIDs += hop.id
                    }
                    val (withChain, chainId) = next.addChain(name = payload.name)
                    importedChainId = chainId
                    next = withChain.copy(
                        chains = withChain.chains.map { chain ->
                            if (chain.id == chainId) chain.copy(hopIDs = hopIDs) else chain
                        },
                        selectedChainID = chainId,
                    )
                    val label = payload.name.trim().ifEmpty { "Untitled chain" }
                    message = "Imported chain $label with ${payload.hops.size} server(s)."
                    next
                }
                is com.aengix.hopper.data.HopperConf.Payload.Key -> {
                    message = "Imported key ${payload.key.displayName}."
                    next.importDeployKey(payload.key)
                }
            }
        }
        importedChainId?.let { chainId ->
            if (announceChain) {
                _chainImportPrompt.value = ChainImportPrompt(chainId = chainId, message = message)
            }
        }
        return message
    }

    data class ChainImportPrompt(
        val chainId: String,
        val message: String,
    )

    private val _chainImportPrompt = MutableStateFlow<ChainImportPrompt?>(null)
    val chainImportPrompt: StateFlow<ChainImportPrompt?> = _chainImportPrompt.asStateFlow()

    fun dismissChainImportPrompt() {
        _chainImportPrompt.value = null
    }

    fun setError(message: String?) {
        _errorMessage.value = message
    }

    private val _pendingHopperConfBytes = MutableStateFlow<ByteArray?>(null)
    val pendingHopperConfBytes: StateFlow<ByteArray?> = _pendingHopperConfBytes.asStateFlow()

    private val _pendingLanInvite = MutableStateFlow<HopperLanInvite?>(null)
    val pendingLanInvite: StateFlow<HopperLanInvite?> = _pendingLanInvite.asStateFlow()

    fun offerLanInvite(invite: HopperLanInvite) {
        _pendingLanInvite.value = invite
    }

    fun clearLanInvite() {
        _pendingLanInvite.value = null
    }

    fun handleScannedQr(text: String): Boolean {
        HopperLanInvite.parse(text)?.let {
            _pendingLanInvite.value = it
            return true
        }
        return runCatching {
            importPayload(HopperConf.parsePayloadJson(text))
            true
        }.getOrElse {
            _errorMessage.value = it.message
            false
        }
    }

    fun offerHopperConfBytes(bytes: ByteArray) {
        if (HopperConf.isHopperConfFile(bytes)) {
            _pendingHopperConfBytes.value = bytes
        } else {
            runCatching {
                val payload = HopperConf.parsePayloadJson(String(bytes, Charsets.UTF_8))
                importPayload(payload)
            }.onFailure {
                _errorMessage.value = it.message
            }
        }
    }

    fun clearPendingHopperConf() {
        _pendingHopperConfBytes.value = null
    }

    fun importPendingHopperConf(password: String) {
        val bytes = _pendingHopperConfBytes.value ?: return
        runCatching {
            val payload = HopperConf.decryptFile(bytes, password)
            importPayload(payload)
            _pendingHopperConfBytes.value = null
        }.onFailure {
            _errorMessage.value = it.message
        }
    }

    fun cancelServerUpdate() {
        _serverUpdatePrompt.value = null
        pendingConnectRestart = false
    }

    fun fetchChainStatus(chainId: String) {
        val chain = _state.value.chains.firstOrNull { it.id == chainId } ?: return
        val hops = _state.value.resolveHops(chain)
        viewModelScope.launch {
            val reports = withContext(Dispatchers.IO) {
                hops.map { hop ->
                    async {
                        runCatching { VersionService.fetchChainStatus(hop, chainId) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            _chainStatusReports.update { it + (chainId to reports) }
        }
    }

    fun connect(restartHopperd: Boolean = false) {
        val chain = _state.value.selectedChain
        val entry = _state.value.entryHop
        if (chain == null || entry == null) {
            reportError("Select a chain with at least one server (entry → exit).")
            return
        }

        _errorMessage.value = null
        _provisionStatus.value = null
        ProfileStore.clearLastTunnelError()

        val hops = _state.value.activeHops
        viewModelScope.launch {
            try {
                _provisionStatus.value = "Checking server versions…"
                when (val outcome = withContext(Dispatchers.IO) { preflightVersions(hops) }) {
                    is PreflightResult.AppTooOld -> {
                        _provisionStatus.value = null
                        reportError("App is too old. Update to ${outcome.required} or newer.")
                        return@launch
                    }
                    is PreflightResult.ServerTooOld -> {
                        _provisionStatus.value = null
                        _serverUpdatePrompt.value = ServerUpdatePrompt(
                            hopNames = outcome.hops.map { it.displayName },
                            hops = outcome.hops,
                            targetVersion = HopVersion.appVersion(getApplication()),
                        )
                        pendingConnectRestart = restartHopperd
                        return@launch
                    }
                    PreflightResult.Compatible -> Unit
                }

                performConnect(chain.id, entry, hops, restartHopperd)
            } catch (error: Throwable) {
                _provisionStatus.value = null
                reportError(HopErrorDetails.describe(error))
            }
        }
    }

    fun confirmServerUpdate() {
        val prompt = _serverUpdatePrompt.value ?: return
        _serverUpdatePrompt.value = null
        _errorMessage.value = null
        _provisionStatus.value = "Updating servers…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    prompt.hops.forEach { hop ->
                        VersionService.updateServer(hop, prompt.targetVersion)
                    }
                }
                val chain = _state.value.selectedChain ?: return@launch
                val entry = _state.value.entryHop ?: return@launch
                performConnect(chain.id, entry, _state.value.activeHops, pendingConnectRestart)
            } catch (error: Throwable) {
                _provisionStatus.value = null
                reportError(HopErrorDetails.describe(error))
            }
        }
    }

    private suspend fun performConnect(
        chainId: String,
        entry: HopNodeProfile,
        hops: List<HopNodeProfile>,
        restartHopperd: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            _provisionStatus.value = "Provisioning chain (exit → entry)…"
            ChainProvisioner.provision(
                chainId = chainId,
                chain = hops,
                restartHopperd = restartHopperd,
            ) { index, total, message ->
                _provisionStatus.value = "[${total - index}/$total] $message"
            }
        }

        _provisionStatus.value = "Starting VPN…"
        val context = getApplication<android.app.Application>()
        val prepareIntent = VpnService.prepare(context)
        val connectContext = TunnelConnectContext(
            chainId = chainId,
            hopperPort = ChainTopology.listenPort(chainId),
            overlayCIDR = ChainTopology.overlayCIDR(chainId),
            deviceId = ProfileStore.deviceId(),
        )
        PendingVpnConnect.pendingHopJson = TunnelBootstrap.hopJson(entry)
        PendingVpnConnect.pendingContextJson = TunnelBootstrap.contextJson(connectContext)
        if (prepareIntent != null) {
            _provisionStatus.value = null
            if (PendingVpnConnect.permissionGranted) {
                completePendingConnect()
            } else {
                onVpnPermissionRequired?.invoke(prepareIntent)
                    ?: TunnelLog.error("VPN permission required but no handler registered")
            }
            return
        }

        startVpnService(entry, connectContext)
        _provisionStatus.value = null
    }

    private sealed interface PreflightResult {
        data object Compatible : PreflightResult
        data class AppTooOld(val required: String) : PreflightResult
        data class ServerTooOld(val hops: List<HopNodeProfile>) : PreflightResult
    }

    private fun preflightVersions(hops: List<HopNodeProfile>): PreflightResult {
        val appVersion = HopVersion.appVersion(getApplication())
        val infos = hops.map { hop ->
            hop to VersionService.fetchServerVersion(hop)
        }

        for ((_, info) in infos) {
            info.min_app_version?.let { minApp ->
                if (SemVer.compare(appVersion, minApp) < 0) {
                    return PreflightResult.AppTooOld(minApp)
                }
            }
        }

        val outdated = infos.mapNotNull { (hop, info) ->
            val serverVersion = info.version ?: return@mapNotNull null
            if (SemVer.compare(serverVersion, HopVersion.minServerVersion) >= 0) null else hop
        }

        if (outdated.isNotEmpty()) {
            return PreflightResult.ServerTooOld(outdated)
        }
        return PreflightResult.Compatible
    }

    fun onVpnPermissionGranted() {
        PendingVpnConnect.permissionGranted = true
        completePendingConnect()
    }

    private fun completePendingConnect() {
        val hopJson = PendingVpnConnect.pendingHopJson ?: return
        val contextJson = PendingVpnConnect.pendingContextJson ?: return
        PendingVpnConnect.clear()
        val hop = Json.decodeFromString(HopNodeProfile.serializer(), hopJson)
        val context = Json.decodeFromString<TunnelConnectContext>(contextJson)
        startVpnService(hop, context)
    }

    fun disconnect() {
        val context = getApplication<android.app.Application>()
        context.startService(
            Intent(context, HopperVpnService::class.java).apply {
                action = HopperVpnService.ACTION_DISCONNECT
            },
        )
        _vpnStatus.value = VpnStatus.Disconnecting
    }

    fun readTunnelErrorIfNeeded() {
        ProfileStore.loadLastTunnelError()?.let { reportError(it) }
    }

    private fun startVpnService(entry: HopNodeProfile, context: TunnelConnectContext) {
        val appContext = getApplication<android.app.Application>()
        appContext.startForegroundService(
            Intent(appContext, HopperVpnService::class.java).apply {
                action = HopperVpnService.ACTION_CONNECT
                putExtra(HopperVpnService.EXTRA_HOP_JSON, TunnelBootstrap.hopJson(entry))
                putExtra(HopperVpnService.EXTRA_CONTEXT_JSON, TunnelBootstrap.contextJson(context))
            },
        )
    }

    private fun updateState(block: (AppState) -> AppState) {
        _state.update { current ->
            val next = block(current)
            ProfileStore.save(next)
            next
        }
    }

    private fun reportError(message: String) {
        val detail = HopErrorDetails.describeMessage(message)
        _errorMessage.value = detail
        TunnelLog.error("[App] $detail")
    }

    override fun onCleared() {
        VpnStatusBus.removeListener(statusListener)
        super.onCleared()
    }
}

object PendingVpnConnect {
    var pendingHopJson: String? = null
    var pendingContextJson: String? = null
    @Volatile var permissionGranted: Boolean = false

    fun clear() {
        pendingHopJson = null
        pendingContextJson = null
        permissionGranted = false
    }
}

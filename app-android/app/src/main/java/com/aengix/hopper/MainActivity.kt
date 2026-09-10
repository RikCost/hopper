package com.aengix.hopper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.aengix.hopper.data.HopperLanInvite
import com.aengix.hopper.ui.HopperNavHost
import com.aengix.hopper.ui.Routes
import com.aengix.hopper.util.TunnelLog
import com.aengix.hopper.vpn.VpnController

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_SEED_DEMO = "seed_demo"
    }
    private val vpnController: VpnController by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingVpnAction?.invoke() ?: vpnController.onVpnPermissionGranted()
            pendingVpnAction = null
        } else {
            pendingVpnAction = null
            vpnController.setError("VPN permission was denied.")
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingCameraScan?.invoke()
        } else {
            vpnController.setError("Camera permission is required to scan QR codes.")
        }
        pendingCameraScan = null
    }

    private var pendingVpnAction: (() -> Unit)? = null
    private var pendingCameraScan: (() -> Unit)? = null

    fun requestVpnConnect(restartHopperd: Boolean) {
        requestVpnPermission {
            vpnController.connect(restartHopperd = restartHopperd)
        }
    }

    private fun requestVpnPermission(onGranted: () -> Unit) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            onGranted()
            return
        }
        TunnelLog.info("Launching VPN permission dialog")
        pendingVpnAction = onGranted
        vpnPermissionLauncher.launch(prepareIntent)
    }

    fun requestCameraPermission(onGranted: () -> Unit) {
        when {
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> onGranted()
            else -> {
                pendingCameraScan = onGranted
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        vpnController.onVpnPermissionRequired = { intent ->
            TunnelLog.info("Launching VPN permission dialog after provisioning")
            vpnPermissionLauncher.launch(intent)
        }

        val startDestination = resolveStartDestination(intent)
        if (intent?.getBooleanExtra(EXTRA_SEED_DEMO, false) == true) {
            vpnController.loadDemoData()
        }
        handleIncomingIntent(intent)

        setContent {
            val navController = rememberNavController()
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HopperNavHost(
                        navController = navController,
                        vpn = vpnController,
                        onRequestVpnConnect = ::requestVpnConnect,
                        onRequestCameraPermission = ::requestCameraPermission,
                        startDestination = startDestination,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme.equals(HopperLanInvite.SCHEME, ignoreCase = true)) {
                    val invite = HopperLanInvite.parse(uri)
                    if (invite != null) {
                        vpnController.offerLanInvite(invite)
                    } else {
                        vpnController.setError("Not a valid Hopper remote-import link.")
                    }
                } else {
                    readAndOffer(uri)
                }
            }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    readAndOffer(uri)
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val invite = text?.let { HopperLanInvite.parse(it) }
                    if (invite != null) {
                        vpnController.offerLanInvite(invite)
                    } else {
                        text?.toByteArray(Charsets.UTF_8)?.let {
                            vpnController.offerHopperConfBytes(it)
                        }
                    }
                }
            }
        }
    }

    private fun readAndOffer(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read shared file")
        }.onSuccess { bytes ->
            vpnController.offerHopperConfBytes(bytes)
        }.onFailure {
            vpnController.setError(it.message)
        }
    }

    private fun resolveStartDestination(intent: Intent?): String {
        val route = intent?.getStringExtra(EXTRA_ROUTE)?.trim().orEmpty()
        if (route.isEmpty()) return Routes.HOME
        return when (route) {
            "home" -> Routes.HOME
            "chains" -> Routes.CHAINS
            "servers" -> Routes.SERVERS
            else -> route
        }
    }

    override fun onResume() {
        super.onResume()
        vpnController.readTunnelErrorIfNeeded()
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key) as? T
    }
}

package com.aengix.hopper.model

import android.content.Context

object HopConstants {
    const val APP_DISPLAY_NAME = "ɹǝddoH"
    const val PROFILE_STORE_FILE_NAME = "hopper-profiles.json"
    const val TUNNEL_LAST_ERROR_FILE_NAME = "last-tunnel-error.txt"
    const val DEVICE_ID_KEY = "hopper-device-id"

    const val TUNNEL_IPV4_MASK_BITS = 24
    const val TUNNEL_REMOTE_ADDRESS = "10.64.0.1"
    const val TUNNEL_IPV6_SINKHOLE = "fd00::1"

    const val TUNNEL_MTU = 1280

    const val DEFAULT_SSH_PORT = 22
    const val DEFAULT_INSTALL_DIR = "~/hopper"

    const val SERVER_INSTALL_REPO = "ZonD80/hopper"
    const val SERVER_INSTALL_REF = "main"
    const val SERVER_INSTALL_URL =
        "https://raw.githubusercontent.com/$SERVER_INSTALL_REPO/$SERVER_INSTALL_REF/server/install.sh"

    const val OVERLAY_NODE_OCTET = 10

    fun appVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "3.0.0"
}

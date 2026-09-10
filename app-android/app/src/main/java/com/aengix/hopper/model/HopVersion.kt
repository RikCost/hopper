package com.aengix.hopper.model

import android.content.Context
import kotlinx.serialization.Serializable

@Serializable
data class VersionManifest(
    val version: String,
    val min_app_version: String,
    val min_server_version: String,
    val protocol_version: Int = 2,
)

@Serializable
data class ServerVersionInfo(
    val version: String? = null,
    val min_app_version: String? = null,
)

object HopVersion {
    const val minServerVersion = "2.0.0"
    const val protocolVersion = 2

    fun appVersion(context: Context): String = HopConstants.appVersion(context)

    fun manifest(context: Context) = VersionManifest(
        version = appVersion(context),
        min_app_version = appVersion(context),
        min_server_version = minServerVersion,
        protocol_version = protocolVersion,
    )
}

object SemVer {
    fun compare(lhs: String, rhs: String): Int {
        val a = parts(lhs)
        val b = parts(rhs)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun parts(value: String): List<Int> =
        value.split('.').take(3).mapNotNull { it.toIntOrNull() }
}

enum class VersionCheckOutcome {
    Compatible,
    AppTooOld,
    ServerTooOld,
}

data class ServerUpdatePrompt(
    val hopNames: List<String>,
    val hops: List<HopNodeProfile>,
    val targetVersion: String,
)

@Serializable
data class TunnelConnectContext(
    val chainId: String,
    val hopperPort: Int,
    val overlayCIDR: String,
    val deviceId: String,
)

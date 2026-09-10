package com.aengix.hopper.tunnel

import com.aengix.hopper.ssh.SSHByteStream
import com.aengix.hopper.util.HopErrorDetails
import com.aengix.hopper.util.IPv4Only
import com.aengix.hopper.util.TunnelLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class IPTunnelEngine(
    private val stream: SSHByteStream,
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val dropNonIPv4: Boolean = false,
) {
    private val running = AtomicBoolean(false)
    private val threads = mutableListOf<Thread>()
    var onFailure: ((String) -> Unit)? = null

    @Volatile
    private var pendingInbound = ByteArray(0)
    private val bufferLock = Any()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        TunnelLog.info("IPTunnelEngine starting")
        threads += startThread("tun-to-ssh") { tunToSSH() }
        threads += startThread("ssh-to-tun") { sshToTun() }
        threads += startThread("keepalive") { keepaliveLoop() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        threads.forEach { it.interrupt() }
        threads.clear()
        stream.close()
        TunnelLog.info("IPTunnelEngine stopped")
    }

    private fun tunToSSH() {
        val buffer = ByteArray(32_767)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val read = tunInput.read(buffer)
                if (read < 0) {
                    if (!running.get()) return
                    fail("TUN closed")
                    return
                }
                if (read == 0) {
                    Thread.sleep(5)
                    continue
                }
                if (dropNonIPv4 && !IPv4Only.isIPv4Packet(buffer, read)) {
                    continue
                }
                val frame = IPTunnelFrame(IPTunnelFrameType.DATA, buffer.copyOf(read))
                stream.write(frame.encoded())
            } catch (error: Exception) {
                if (!running.get()) return
                fail("TUN write to SSH failed: ${HopErrorDetails.describe(error)}")
                return
            }
        }
    }

    private fun sshToTun() {
        val readBuffer = ByteArray(32_768)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val read = stream.read(readBuffer, 0, readBuffer.size)
                if (read < 0) {
                    if (!running.get()) return
                    fail("SSH read failed: stream closed")
                    return
                }
                if (read == 0) continue

                synchronized(bufferLock) {
                    pendingInbound = pendingInbound + readBuffer.copyOf(read)
                    processInbound()
                }
            } catch (error: Exception) {
                if (!running.get()) return
                fail("SSH read failed: ${HopErrorDetails.describe(error)}")
                return
            }
        }
    }

    private fun processInbound() {
        var cursor = pendingInbound
        while (true) {
            if (cursor.size < IPTunnelFrame.HEADER_LENGTH) {
                pendingInbound = cursor
                return
            }

            if (cursor[0] != IPTunnelFrameType.WIRE_VERSION) {
                fail("Invalid iptunnel frame: badVersion (head=${hexPreview(cursor)})")
                return
            }

            if (IPTunnelFrameType.fromRaw(cursor[1]) == null) {
                fail("Invalid iptunnel frame: badType (head=${hexPreview(cursor)})")
                return
            }

            val payloadLength = ((cursor[2].toInt() and 0xFF) shl 8) or (cursor[3].toInt() and 0xFF)
            if (payloadLength > IPTunnelFrame.MAX_PACKET_LENGTH) {
                fail("Invalid iptunnel frame: packetTooLarge ($payloadLength)")
                return
            }

            val frameLength = IPTunnelFrame.HEADER_LENGTH + payloadLength
            if (cursor.size < frameLength) {
                pendingInbound = cursor
                return
            }

            val frameData = cursor.copyOfRange(0, frameLength)
            cursor = cursor.copyOfRange(frameLength, cursor.size)

            try {
                val frame = IPTunnelFrame.decode(frameData)
                when (frame.type) {
                    IPTunnelFrameType.DATA -> {
                        tunOutput.write(frame.payload)
                        tunOutput.flush()
                    }
                    IPTunnelFrameType.KEEPALIVE,
                    IPTunnelFrameType.ASSIGN_REQ,
                    IPTunnelFrameType.ASSIGN_RESP,
                    -> Unit
                }
            } catch (error: Exception) {
                fail("Invalid iptunnel frame: ${error.message} (head=${hexPreview(frameData)})")
                return
            }
        }
    }

    private fun keepaliveLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(25_000)
                if (!running.get()) return
                stream.write(IPTunnelFrame(IPTunnelFrameType.KEEPALIVE).encoded())
            } catch (error: Exception) {
                if (!running.get()) return
                fail("SSH keepalive failed: ${HopErrorDetails.describe(error)}")
                return
            }
        }
    }

    private fun fail(message: String) {
        TunnelLog.error(message)
        onFailure?.invoke(message)
        stop()
    }

    private fun hexPreview(data: ByteArray, limit: Int = 16): String =
        data.take(limit).joinToString(" ") { "%02x".format(it) }

    private fun startThread(name: String, block: () -> Unit): Thread =
        Thread(block, name).also { it.start() }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray =
    ByteArray(size + other.size).also { result ->
        copyInto(result)
        other.copyInto(result, size)
    }

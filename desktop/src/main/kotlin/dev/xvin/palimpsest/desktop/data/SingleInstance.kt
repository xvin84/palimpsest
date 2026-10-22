package dev.xvin.palimpsest.desktop.data

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * One Palimpsest at a time: with the window hidden in the tray, launching the app again shows the
 * running one instead of starting a second player. The first instance holds a lock file and listens
 * on a loopback port written next to it; a later launch finds the lock taken, sends "show" there
 * and quits. The OS drops the lock when a process dies, so a crash never leaves it stuck.
 */
object SingleInstance {
    private val lockFile = File(Paths.config, "instance.lock")
    private val portFile = File(Paths.config, "instance.port")
    private var lock: FileLock? = null

    /** True when this is the only instance; then [onShow] runs whenever another launch asks for the window. */
    fun claim(onShow: () -> Unit): Boolean {
        val channel = runCatching { RandomAccessFile(lockFile, "rw").channel }.getOrNull() ?: return true
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) {
            // Another instance runs: ask it to show itself. If it can't be reached, run anyway.
            val asked = runCatching {
                Socket(InetAddress.getLoopbackAddress(), portFile.readText().trim().toInt()).use { it.getOutputStream().write("show\n".toByteArray()) }
            }.isSuccess
            if (asked) return false
            return true
        }
        runCatching {
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            thread(name = "palimpsest-instance", isDaemon = true) {
                while (true) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { s.use { if (it.getInputStream().bufferedReader().readLine() == "show") onShow() } }
                }
            }
        }
        return true
    }
}

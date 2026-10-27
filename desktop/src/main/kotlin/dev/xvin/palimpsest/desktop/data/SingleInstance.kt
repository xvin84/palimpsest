package dev.xvin.palimpsest.desktop.data

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One Palimpsest at a time: with the window hidden in the tray, launching the app again shows the
 * running one instead of starting a second player. The first instance holds a lock file and listens
 * on a loopback port written next to it (with its process id); a later launch finds the lock taken,
 * sends "show" there and quits once the window answers "shown". The OS drops the lock when a process
 * dies, so a crash never leaves it stuck — but a process that hangs on without a working window
 * would, so one that doesn't answer is ended and this launch takes its place.
 */
object SingleInstance {
    private val lockFile = File(Paths.config, "instance.lock")
    private val portFile = File(Paths.config, "instance.port")
    private var lock: FileLock? = null

    /**
     * True when this is the only instance; then [onShow] runs whenever another launch asks for the
     * window, and says whether the window came up.
     */
    fun claim(onShow: () -> Boolean): Boolean {
        val channel = runCatching { RandomAccessFile(lockFile, "rw").channel }.getOrNull() ?: return true
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) {
            val parts = runCatching { portFile.readText().trim().split(' ') }.getOrNull().orEmpty()
            val port = parts.getOrNull(0)?.toIntOrNull()
            val pid = parts.getOrNull(1)?.toLongOrNull()
            val reply = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port!!), 2000)
                    s.soTimeout = 25_000
                    s.getOutputStream().write("show\n".toByteArray())
                    s.getOutputStream().flush()
                    s.getInputStream().bufferedReader().readLine()
                }
            }
            if (reply.getOrNull() == "shown") return false
            // 0.4.9 and older don't answer and don't write their id: trust them, as before.
            if (pid == null) return !reply.isSuccess
            val other = ProcessHandle.of(pid).orElse(null)?.takeIf { it.isOurs() }
            CrashGuard.write("The running Palimpsest (pid $pid) didn't bring its window up (${reply.exceptionOrNull() ?: reply.getOrNull()}); ending it", null)
            other?.destroyForcibly()
            runCatching { other?.onExit()?.get(5, TimeUnit.SECONDS) }
            lock = runCatching { channel.tryLock() }.getOrNull() ?: return true
        }
        runCatching {
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            portFile.writeText("${server.localPort} ${ProcessHandle.current().pid()}")
            thread(name = "palimpsest-instance", isDaemon = true) {
                while (true) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        s.use {
                            if (it.getInputStream().bufferedReader().readLine() != "show") return@use
                            val shown = runCatching(onShow).getOrDefault(false)
                            it.getOutputStream().write((if (shown) "shown\n" else "stuck\n").toByteArray())
                        }
                    }
                }
            }
        }
        return true
    }

    /** A process id can be reused: end only something that looks like this app. */
    private fun ProcessHandle.isOurs(): Boolean {
        val cmd = info().command().orElse("").substringAfterLast('/').substringAfterLast('\\').lowercase()
        return "palimpsest" in cmd || cmd == "java" || cmd == "java.exe" || cmd == "javaw.exe"
    }
}

package dev.xvin.palimpsest.desktop.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * What happens when the app breaks. Every uncaught error goes to `crash.log` in the settings folder;
 * a start that fails says so in a window instead of vanishing; and when the last start never got
 * as far as drawing its window (the JVM died under it — a graphics driver, say), this one draws
 * without the video card and says why. The JVM's own crash report (`-XX:ErrorFile`, see the build)
 * is folded into the log on the next start.
 */
object CrashGuard {
    val log = File(Paths.config, "crash.log")
    private val starting = File(Paths.config, "starting")
    /** Present: draw without the video card (Settings can turn it off again). */
    val safeRenderFile = File(Paths.config, "safe-render")

    /** This start draws in software because an earlier one died before its window was up. */
    var fellBack = false
        private set

    val version: String get() = System.getProperty("jpackage.app-version") ?: "dev"

    /** First thing in main: catch everything from here on. */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { th, e -> write("Uncaught in thread «${th.name}»", e) }
        collectNativeCrashes()
    }

    /** Once this process is the one that runs (not a second launch): check the last start, mark this one. */
    fun beginStart() {
        if (starting.exists() && !safeRenderFile.exists()) {
            write("The previous start did not get to its window (${starting.readText().trim()}); drawing without the video card from now on", null)
            runCatching { safeRenderFile.writeText("set after a failed start\n") }
            fellBack = true
        }
        if (safeRenderFile.exists()) System.setProperty("skiko.renderApi", "SOFTWARE")
        runCatching { starting.writeText("$version, ${now()}") }
    }

    /** The window is up and has drawn for a while: this start went fine. */
    fun started() { starting.delete() }

    /** An error the app can't go on after: logged, shown, and the process ends (so no half-dead one is left holding the lock). */
    fun fatal(where: String, e: Throwable): Nothing {
        write(where, e)
        starting.delete()
        runCatching { showDialog(e) }
        exitProcess(1)
    }

    @Synchronized
    fun write(where: String, e: Throwable?) {
        runCatching {
            // Only the recent past matters: keep the file small.
            if (log.length() > 512 * 1024) log.writeText(log.readText().takeLast(256 * 1024))
            val trace = e?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() }.orEmpty()
            log.appendText(
                "=== ${now()} · Palimpsest $version · ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}" +
                    " · Java ${System.getProperty("java.version")} · render ${System.getProperty("skiko.renderApi") ?: "auto"}\n$where\n$trace\n",
            )
        }
        System.err.println("Palimpsest: $where"); e?.printStackTrace()
    }

    private fun now() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /** Where `-XX:ErrorFile` puts a JVM crash: beside the app, or the temp folder when that isn't writable. */
    private fun collectNativeCrashes() {
        val appDir = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile }
        listOfNotNull(appDir, File(System.getProperty("java.io.tmpdir")))
            .flatMap { it.listFiles { f -> f.name.startsWith("palimpsest-crash-") && f.name.endsWith(".log") }.orEmpty().toList() }
            .forEach { f ->
                runCatching {
                    // The head says what crashed and where; the rest is a memory dump nobody reads.
                    write("The JVM crashed (${f.name}):\n" + f.readLines().take(60).joinToString("\n"), null)
                    f.delete()
                }
            }
    }

    private fun showDialog(e: Throwable) {
        val ru = java.util.Locale.getDefault().language == "ru"
        val text = (if (ru) "Palimpsest не смог продолжить работу и закроется.\n\n" else "Palimpsest ran into an error and will close.\n\n") +
            "${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}\n\n" +
            (if (ru) "Подробности записаны в\n${log.absolutePath}\n— пришли этот файл автору." else "The details are in\n${log.absolutePath}\n— please send this file to the author.")
        val open = if (ru) "Открыть папку" else "Open the folder"
        val close = if (ru) "Закрыть" else "Close"
        val choice = javax.swing.JOptionPane.showOptionDialog(
            null, text, "Palimpsest", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.ERROR_MESSAGE, null, arrayOf(open, close), close,
        )
        if (choice == 0) runCatching { java.awt.Desktop.getDesktop().open(Paths.config) }
    }
}

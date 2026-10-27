package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.library.YandexLinks
import dev.xvin.palimpsest.core.model.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The file work behind a track's menu: the system trash, moving files, earlier versions, links. */
object FileOps {
    /** Into the system trash: Java's own where it has one (Windows, macOS), the freedesktop.org trash on Linux. */
    fun trash(f: File): Boolean {
        if (!f.exists()) return false
        val desktop = runCatching { java.awt.Desktop.getDesktop() }.getOrNull()
        if (desktop != null && runCatching { desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) && desktop.moveToTrash(f) }.getOrDefault(false)) return true
        if (Paths.isWindows || Paths.isMac) return false
        return runCatching { freedesktopTrash(f) }.getOrDefault(false)
    }

    /** The home trash of the freedesktop.org spec: the file in `files/`, where it came from in `info/`. */
    private fun freedesktopTrash(f: File): Boolean {
        val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        val files = File(data, "Trash/files").apply { mkdirs() }
        val info = File(data, "Trash/info").apply { mkdirs() }
        var name = f.name
        var n = 1
        while (File(files, name).exists() || File(info, "$name.trashinfo").exists()) {
            n++
            name = if (f.extension.isEmpty()) "${f.name}.$n" else "${f.nameWithoutExtension}.$n.${f.extension}"
        }
        val path = f.absolutePath.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val note = File(info, "$name.trashinfo")
        note.writeText("[Trash Info]\nPath=$path\nDeletionDate=${java.time.LocalDateTime.now().withNano(0)}\n")
        if (move(f, File(files, name))) return true
        note.delete()
        return false
    }

    /** Moves a file, across disks too. */
    fun move(from: File, to: File): Boolean = runCatching {
        to.parentFile?.mkdirs()
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }.recoverCatching { Files.move(from.toPath(), to.toPath()) }.isSuccess

    /** [name] in [dir], or «name (2).ext», «name (3).ext»… when it's taken. */
    fun freeName(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
        return f
    }

    /** Lyrics and other files that share the audio's name (`song.lrc` next to `song.flac`). */
    fun companions(f: File): List<File> =
        listOf("lrc", "txt").map { File(f.parentFile, "${f.nameWithoutExtension}.$it") }.filter { it.isFile }

    private val stamp = Regex("""^\d{13}_""")
    private fun sameSong(name: String) = name.replace(stamp, "").removeSuffix(" (FLAC)").lowercase()

    /**
     * Earlier files of this track kept in `.palimpsest/replaced` next to it (where «Upgrade to FLAC»
     * and restoring a version put the file they replace), newest first.
     */
    fun versions(t: Track): List<File> {
        val f = File(t.path ?: return emptyList())
        val dir = File(f.parentFile, ".palimpsest/replaced")
        val key = sameSong(f.nameWithoutExtension)
        return dir.listFiles()?.filter { it.isFile && sameSong(it.nameWithoutExtension) == key }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    /** [old] takes the track's place; the current file goes to `.palimpsest/replaced`. The file now playing as the track. */
    fun restore(t: Track, old: File): File? {
        val current = File(t.path ?: return null)
        if (!current.isFile || !old.isFile) return null
        val keep = File(current.parentFile, ".palimpsest/replaced").apply { mkdirs() }
        val kept = File(keep, current.name).let { if (it.exists()) File(keep, "${System.currentTimeMillis()}_${current.name}") else it }
        if (!move(current, kept)) return null
        val target = freeName(current.parentFile, old.name.replace(stamp, ""))
        if (!move(old, target)) { move(kept, current); return null }
        return target
    }

    /** The track's page on its service. */
    fun webLink(t: Track): String? {
        val id = t.remoteId ?: return null
        return when (t.service) {
            Service.YANDEX -> YandexLinks.trackUrl(t.remoteAlbumId, id)
            Service.YOUTUBE -> "https://music.youtube.com/watch?v=$id"
            Service.BANDCAMP -> if (id.startsWith("http")) id else "https://$id"
            else -> null
        }
    }

    fun copyToClipboard(text: String) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
    }

    /** Opens the folder of [f] in the file manager. */
    /**
     * Opens the file manager on [f] with the file selected where the system can do that. Each OS
     * by its own tool: Java's Desktop.open often does nothing on Linux without GNOME's libraries.
     * A folder is opened as itself.
     */
    fun showInFolder(f: File) {
        val path = f.absolutePath
        fun run(vararg cmd: String): Boolean = runCatching { ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && true }.getOrDefault(false)
        fun runOk(vararg cmd: String): Boolean = runCatching { ProcessBuilder(*cmd).redirectErrorStream(true).start().let { it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && it.exitValue() == 0 } }.getOrDefault(false)
        // The freedesktop file-manager interface (Dolphin, Nautilus, Nemo, Thunar…): it opens folders and
        // selects files, and unlike xdg-open it can't land in whatever app claimed «folders» (a music player).
        fun fileManager(method: String) = runOk(
            "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1", "--type=method_call",
            "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.$method", "array:string:${f.toURI()}", "string:",
        )
        val ok = when {
            Paths.isWindows -> if (f.isDirectory) run("explorer", path) else run("explorer", "/select,", path)
            Paths.isMac -> if (f.isDirectory) runOk("open", path) else runOk("open", "-R", path)
            f.isDirectory -> fileManager("ShowFolders") || runOk("xdg-open", path)
            else -> fileManager("ShowItems") || runOk("xdg-open", f.parent ?: path)
        }
        if (!ok) runCatching { java.awt.Desktop.getDesktop().open(if (f.isDirectory) f else f.parentFile) }
    }
}

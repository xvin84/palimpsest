package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.net.Http
import kotlinx.serialization.KSerializer
import java.io.File

/** Where the desktop app keeps its things, per the OS's conventions. */
object Paths {
    private val os = System.getProperty("os.name").lowercase()
    private val home = System.getProperty("user.home")

    val config: File = when {
        "win" in os -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Palimpsest")
        "mac" in os -> File(home, "Library/Application Support/Palimpsest")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "palimpsest-desktop")
    }.apply { mkdirs() }

    val cache: File = when {
        "win" in os -> File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "Palimpsest/cache")
        "mac" in os -> File(home, "Library/Caches/Palimpsest")
        else -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "palimpsest-desktop")
    }.apply { mkdirs() }

    val music: File = File(home, "Music")
    val isWindows = "win" in os
    val isMac = "mac" in os
}

/** A JSON file: read once, written atomically (temp file + rename), so a crash can't leave half a file. */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    fun load(): T = runCatching { Http.json.decodeFromString(serializer, file.readText()) }.getOrElse { default() }

    @Synchronized
    fun save(value: T) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Http.json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

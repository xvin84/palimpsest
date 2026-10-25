package dev.xvin.palimpsest.desktop.data

import java.awt.event.KeyEvent as Awt

/**
 * Everything a key can do. The defaults follow Yandex Music on the web (K pause, J/L seek, N/P
 * tracks, F/D like and dislike, R repeat, S shuffle, M mute, W the big player) plus the app's own
 * (Space, Ctrl+arrows, Ctrl+F…); some actions start without a key, to be given one in Settings.
 */
enum class HotAction(val group: Group, vararg defaults: String) {
    PLAY_PAUSE(Group.PLAYBACK, "Space", "K"),
    NEXT(Group.PLAYBACK, "N", "Ctrl+Right"),
    PREVIOUS(Group.PLAYBACK, "P", "Ctrl+Left"),
    SEEK_FORWARD(Group.PLAYBACK, "Right", "L"),
    SEEK_BACK(Group.PLAYBACK, "Left", "J"),
    VOLUME_UP(Group.PLAYBACK, "Up"),
    VOLUME_DOWN(Group.PLAYBACK, "Down"),
    MUTE(Group.PLAYBACK, "M"),
    SHUFFLE(Group.PLAYBACK, "S"),
    REPEAT(Group.PLAYBACK, "R"),
    LIKE(Group.TRACK, "F"),
    DISLIKE(Group.TRACK, "D"),
    TRACK_MENU(Group.TRACK),
    EDIT_TAGS(Group.TRACK),
    TRACK_RADIO(Group.TRACK),
    SHOW_IN_FOLDER(Group.TRACK),
    COPY_LINK(Group.TRACK),
    GO_ARTIST(Group.TRACK),
    GO_ALBUM(Group.TRACK),
    BIG_PLAYER(Group.VIEW, "W"),
    LYRICS(Group.VIEW),
    QUEUE(Group.VIEW),
    FULL_SCREEN(Group.VIEW, "F11"),
    SEARCH(Group.NAVIGATION, "Ctrl+F"),
    LIBRARY(Group.NAVIGATION),
    FAVOURITES(Group.NAVIGATION),
    HISTORY(Group.NAVIGATION),
    SETTINGS(Group.NAVIGATION, "Ctrl+Comma"),
    BACK(Group.NAVIGATION, "Escape"),
    SELECT_ALL(Group.NAVIGATION, "Ctrl+A"),
    MY_WAVE(Group.NAVIGATION),
    HIDE(Group.APP),
    QUIT(Group.APP, "Ctrl+Q"),
    ;

    enum class Group { PLAYBACK, TRACK, VIEW, NAVIGATION, APP }

    /** On a Mac, Cmd stands where Ctrl does elsewhere. */
    val defaults: List<String> = defaults.map { if (Paths.isMac) it.replace("Ctrl+", "Meta+") else it }
}

/** Key strokes as text («Ctrl+Shift+K»): stored in the settings, shown on the keys page. */
object Hotkeys {
    private val names: Map<Int, String> = buildMap {
        for (c in 'A'..'Z') put(c.code, "$c")
        for (c in '0'..'9') put(c.code, "$c")
        for (i in 1..12) put(Awt.VK_F1 + i - 1, "F$i")
        put(Awt.VK_SPACE, "Space"); put(Awt.VK_ENTER, "Enter"); put(Awt.VK_ESCAPE, "Escape"); put(Awt.VK_TAB, "Tab")
        put(Awt.VK_BACK_SPACE, "Backspace"); put(Awt.VK_DELETE, "Delete"); put(Awt.VK_INSERT, "Insert")
        put(Awt.VK_LEFT, "Left"); put(Awt.VK_RIGHT, "Right"); put(Awt.VK_UP, "Up"); put(Awt.VK_DOWN, "Down")
        put(Awt.VK_HOME, "Home"); put(Awt.VK_END, "End"); put(Awt.VK_PAGE_UP, "PageUp"); put(Awt.VK_PAGE_DOWN, "PageDown")
        put(Awt.VK_COMMA, "Comma"); put(Awt.VK_PERIOD, "Period"); put(Awt.VK_SLASH, "Slash"); put(Awt.VK_SEMICOLON, "Semicolon")
        put(Awt.VK_QUOTE, "Quote"); put(Awt.VK_OPEN_BRACKET, "BracketLeft"); put(Awt.VK_CLOSE_BRACKET, "BracketRight")
        put(Awt.VK_BACK_SLASH, "Backslash"); put(Awt.VK_MINUS, "Minus"); put(Awt.VK_EQUALS, "Equals"); put(Awt.VK_BACK_QUOTE, "Backquote")
        put(Awt.VK_ADD, "NumPlus"); put(Awt.VK_SUBTRACT, "NumMinus"); put(Awt.VK_MULTIPLY, "NumStar"); put(Awt.VK_DIVIDE, "NumSlash")
        for (i in 0..9) put(Awt.VK_NUMPAD0 + i, "Num$i")
        put(Awt.VK_PAUSE, "Pause")
    }
    private val codes = names.entries.associate { (k, v) -> v to k }
    private val shown = mapOf(
        "Left" to "←", "Right" to "→", "Up" to "↑", "Down" to "↓", "Comma" to ",", "Period" to ".", "Slash" to "/",
        "Semicolon" to ";", "Quote" to "'", "BracketLeft" to "[", "BracketRight" to "]", "Backslash" to "\\", "Minus" to "-",
        "Equals" to "=", "Backquote" to "`", "Escape" to "Esc", "NumPlus" to "Num +", "NumMinus" to "Num −",
    )

    // A Russian layout on Linux reports Cyrillic letters, not the keys: the key under «л» is still L.
    private const val ru = "йцукенгшщзхъфывапролджэячсмитьбюё"
    private const val en = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"

    /** The key's code as on a US layout (letters typed in Cyrillic map back to their keys). */
    fun usCode(code: Int, char: Char?): Int {
        if (code in names) return code
        val c = (if (code >= 0x01000000) (code - 0x01000000).toChar() else char)?.lowercaseChar() ?: return code
        val i = ru.indexOf(c)
        if (i < 0) return code
        return when (val l = en[i]) {
            in 'a'..'z' -> l.uppercaseChar().code
            else -> names.entries.firstOrNull { shown[it.value] == "$l" }?.key ?: code
        }
    }

    fun isModifier(code: Int) = code in setOf(Awt.VK_SHIFT, Awt.VK_CONTROL, Awt.VK_ALT, Awt.VK_META, Awt.VK_ALT_GRAPH, Awt.VK_WINDOWS, Awt.VK_CAPS_LOCK)

    /** «Ctrl+Shift+K» for a key with its modifiers, or null for a key without a name. */
    fun stroke(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): String? {
        val name = names[code] ?: return null
        return buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            if (shift) append("Shift+")
            if (meta) append("Meta+")
            append(name)
        }
    }

    /** How a stroke reads on screen: «Ctrl + →», «⌘ + F». */
    fun label(stroke: String, ru: Boolean): String = stroke.split('+').joinToString(" + ") { part ->
        when (part) {
            "Meta" -> if (Paths.isMac) "⌘" else "Win"
            "Alt" -> if (Paths.isMac) "⌥" else "Alt"
            "Space" -> if (ru) "Пробел" else "Space"
            else -> shown[part] ?: part
        }
    }

    fun valid(stroke: String) = stroke.substringAfterLast('+') in codes

    /** The keys of every action: the user's own where they changed them, the defaults elsewhere. */
    fun effective(overrides: Map<String, List<String>>): Map<HotAction, List<String>> =
        HotAction.entries.associateWith { a -> overrides[a.name]?.filter(::valid) ?: a.defaults }
}

package dev.xvin.palimpsest.desktop.tray

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicInteger

// --- The D-Bus shapes of StatusNotifierItem and com.canonical.dbusmenu ---

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun ContextMenu(x: Int, y: Int)
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewIcon(path: String) : DBusSignal(path)
    class NewToolTip(path: String) : DBusSignal(path)
}

/** An icon: width, height, ARGB32 bytes. */
class Pixmap(@field:Position(0) @JvmField val width: Int, @field:Position(1) @JvmField val height: Int, @field:Position(2) @JvmField val data: ByteArray) : Struct()

class ToolTip(
    @field:Position(0) @JvmField val iconName: String,
    @field:Position(1) @JvmField val icons: List<Pixmap>,
    @field:Position(2) @JvmField val title: String,
    @field:Position(3) @JvmField val text: String,
) : Struct()

/** A menu item and its children (each a variant holding another node). */
class MenuNode(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val props: Map<String, Variant<*>>,
    @field:Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

class MenuItemProps(@field:Position(0) @JvmField val id: Int, @field:Position(1) @JvmField val props: Map<String, Variant<*>>) : Struct()

class MenuEvent(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()

/** Two return values (D-Bus methods with several out arguments return a tuple). */
class Two<A, B>(@field:Position(0) @JvmField val a: A, @field:Position(1) @JvmField val b: B) : Tuple()

@JvmSuppressWildcards
@DBusInterfaceName("com.canonical.dbusmenu")
interface DbusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Two<UInt32, MenuNode>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProps>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): Two<List<Int>, List<Int>>

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/**
 * The tray icon on Linux, as a StatusNotifierItem: the panel draws our pixmaps and asks for the menu
 * over dbusmenu. Left click shows or hides the window, middle click pauses, the wheel sets the volume.
 */
class SniTray(private val c: TrayController) {
    private var conn: DBusConnection? = null
    private val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
    private val revision = AtomicInteger(1)

    private val item = object : StatusNotifierItem, Properties {
        override fun getObjectPath() = ITEM_PATH
        override fun ContextMenu(x: Int, y: Int) = Unit
        override fun Activate(x: Int, y: Int) { c.release(); onUi { c.showOrHide() } }
        override fun SecondaryActivate(x: Int, y: Int) = onUi { c.playPause() }
        override fun Scroll(delta: Int, orientation: String) {
            if (orientation.equals("horizontal", ignoreCase = true)) return
            // KDE sends wheel angles (120 a notch), others steps; up is positive.
            c.scroll(if (kotlin.math.abs(delta) >= 60) delta / 120f else delta.toFloat())
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A
        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
            val icon = c.icon.value
            val pixmaps = listOf(icon.small, icon.large).map { Pixmap(it.width, it.height, TrayArt.argb(it)) }
            return mapOf(
                "Category" to Variant("ApplicationStatus"),
                "Id" to Variant("palimpsest"),
                "Title" to Variant("Palimpsest"),
                "Status" to Variant("Active"),
                "WindowId" to Variant(0),
                "IconName" to Variant(""),
                "IconPixmap" to Variant(pixmaps, PIXMAPS),
                "OverlayIconName" to Variant(""),
                "OverlayIconPixmap" to Variant(emptyList<Pixmap>(), PIXMAPS),
                "AttentionIconName" to Variant(""),
                "AttentionIconPixmap" to Variant(emptyList<Pixmap>(), PIXMAPS),
                "AttentionMovieName" to Variant(""),
                "ToolTip" to Variant(ToolTip("", emptyList(), "Palimpsest", c.tooltip()), "(sa(iiay)ss)"),
                "ItemIsMenu" to Variant(false),
                "Menu" to Variant(DBusPath(MENU_PATH)),
            )
        }
    }

    private val menu = object : DbusMenu, Properties {
        override fun getObjectPath() = MENU_PATH

        private fun props(line: TrayController.MenuLine): Map<String, Variant<*>> =
            if (line.separator) mapOf("type" to Variant("separator"))
            else mapOf("label" to Variant(line.label.replace("_", "__")), "enabled" to Variant(line.enabled))

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Two<UInt32, MenuNode> {
            c.hold()
            val children = c.menu().map { Variant(MenuNode(it.id, props(it), emptyList()), NODE) }
            val root = MenuNode(0, mapOf("children-display" to Variant("submenu")), if (parentId == 0) children else emptyList())
            return Two(UInt32(revision.get().toLong()), root)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProps> =
            c.menu().filter { it.id in ids }.map { MenuItemProps(it.id, props(it)) }

        override fun GetProperty(id: Int, name: String): Variant<*> =
            c.menu().firstOrNull { it.id == id }?.let { props(it)[name] } ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            when (eventId) {
                "opened" -> c.hold()
                "closed" -> c.release()
                "clicked" -> { c.release(); c.menu().firstOrNull { it.id == id }?.action?.let { onUi(it) } }
            }
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return emptyList()
        }

        override fun AboutToShow(id: Int): Boolean { c.hold(); return false }
        override fun AboutToShowGroup(ids: List<Int>): Two<List<Int>, List<Int>> { c.hold(); return Two(emptyList(), emptyList()) }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A
        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )
    }

    /** Puts the icon in the tray; false when there's no session bus or no tray watching for items. */
    fun start(): Boolean {
        val bus = runCatching { DBusConnectionBuilder.forSessionBus().build() }.getOrNull() ?: return false
        val ok = runCatching {
            val dbus = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            check(dbus.NameHasOwner(WATCHER))
            bus.requestBusName(name)
            bus.exportObject(ITEM_PATH, item)
            bus.exportObject(MENU_PATH, menu)
            conn = bus
            register()
            // A panel that restarts brings a new watcher: introduce ourselves again.
            bus.addSigHandler(DBus.NameOwnerChanged::class.java, DBusSigHandler { s ->
                if (s.name == WATCHER && s.newOwner.isNotEmpty()) runCatching { register() }
            })
        }.isSuccess
        if (!ok) { runCatching { bus.close() }; conn = null }
        return ok
    }

    private fun register() {
        val bus = conn ?: return
        bus.getRemoteObject(WATCHER, "/StatusNotifierWatcher", StatusNotifierWatcher::class.java).RegisterStatusNotifierItem(name)
    }

    fun stop() {
        runCatching { conn?.close() }
        conn = null
    }

    fun iconChanged() = send { StatusNotifierItem.NewIcon(ITEM_PATH) }

    fun menuChanged() {
        send { StatusNotifierItem.NewToolTip(ITEM_PATH) }
        send { DbusMenu.LayoutUpdated(MENU_PATH, UInt32(revision.incrementAndGet().toLong()), 0) }
    }

    private fun send(signal: () -> DBusSignal) {
        val bus = conn ?: return
        runCatching { bus.sendMessage(signal()) }
    }

    /** Window and player work happens on the UI thread, not D-Bus's. */
    private fun onUi(block: () -> Unit) = java.awt.EventQueue.invokeLater(block)

    companion object {
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val PIXMAPS = "a(iiay)"
        const val NODE = "(ia{sv}av)"
    }
}

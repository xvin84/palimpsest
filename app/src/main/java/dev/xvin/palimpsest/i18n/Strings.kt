package dev.xvin.palimpsest.i18n

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Intent
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.model.Service

/**
 * App-wide access to string resources, usable from composables, services and workers alike.
 * Resources follow the per-app language (Android 13+), and changing the language restarts the
 * process ([Language.apply]), so nothing keeps strings of the previous language around.
 */
object Strings {
    @Volatile
    lateinit var app: Application
        private set

    /** Called from [dev.xvin.palimpsest.PalimpsestApp.attachBaseContext]: before content providers and workers start. */
    fun init(app: Application) { this.app = app }
}

fun tr(@StringRes id: Int): String = Strings.app.resources.getString(id)

fun tr(@StringRes id: Int, vararg args: Any?): String = Strings.app.resources.getString(id, *args)

/** "%d tracks"-style quantity string; the count is also the first format argument. */
fun plural(@PluralsRes id: Int, n: Int): String = Strings.app.resources.getQuantityString(id, n, n)

val Service.label: String
    get() = tr(
        when (this) {
            Service.LOCAL -> R.string.service_local
            Service.YANDEX -> R.string.service_yandex
            Service.YOUTUBE -> R.string.service_youtube
            Service.SPOTIFY -> R.string.service_spotify
            Service.BANDCAMP -> R.string.service_bandcamp
        },
    )

/** Built-in EQ presets are stored by their (English) name and shown translated; user profiles as named. */
fun eqPresetLabel(name: String): String = when (EqProfile.canonicalName(name)) {
    "Flat" -> tr(R.string.eq_preset_flat)
    "Bass" -> tr(R.string.eq_preset_bass)
    "Rock" -> tr(R.string.eq_preset_rock)
    "Electronic" -> tr(R.string.eq_preset_electronic)
    "Hip-hop" -> tr(R.string.eq_preset_hiphop)
    "Vocal" -> tr(R.string.eq_preset_vocal)
    "Acoustic" -> tr(R.string.eq_preset_acoustic)
    "Classical" -> tr(R.string.eq_preset_classical)
    "Night" -> tr(R.string.eq_preset_night)
    "Brighter" -> tr(R.string.eq_preset_brighter)
    else -> name
}

/** The app's own language, independent of the system one (Android 13+ per-app locales). */
object Language {
    /** Language tag → its name in that language; "" follows the system. */
    val options = listOf("" to null, "ru" to "Русский", "en" to "English")

    private var started: LocaleList? = null

    fun current(): String = Strings.app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()

    fun set(tag: String) {
        Strings.app.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /**
     * Strings resolved once (tab names, enum labels, notification channels) would otherwise stay
     * in the old language, so a language change restarts the app the next time it's in front.
     */
    fun restartIfChanged(activity: Activity): Boolean {
        val now = activity.resources.configuration.locales
        val first = started ?: now.also { started = it }
        if (first == now) return false
        activity.startActivity(Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
        return true
    }
}

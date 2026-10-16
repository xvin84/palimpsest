package dev.xvin.palimpsest.errors

import android.content.Context
import android.os.Build
import androidx.media3.common.PlaybackException
import dev.xvin.palimpsest.BuildConfig
import dev.xvin.palimpsest.core.model.AuthRequiredException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/** A failure explained for humans: what happened, what to try, and the raw details to copy. */
data class ErrorInfo(
    val title: String,
    val explanation: String,
    val hints: List<String>,
    val details: String,
    val at: Long = System.currentTimeMillis(),
    val context: String? = null,
)

object ErrorExplainer {
    fun explain(t: Throwable, context: String? = null, online: Boolean = true): ErrorInfo {
        val chain = generateSequence(t) { it.cause }.take(8).toList()
        val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        val lower = text.lowercase()
        val youtube = "youtube" in lower || "googlevideo" in lower || "newpipe" in lower || context?.contains("YouTube") == true
        val spotify = "spotify" in lower || context?.contains("Spotify") == true
        val details = details(t, context)

        fun info(title: String, explanation: String, vararg hints: String) = ErrorInfo(title, explanation, hints.toList(), details, context = context)

        val playback = chain.firstNotNullOfOrNull { it as? PlaybackException }
        val networkish = chain.any { it is java.io.IOException } || playback != null
        return when {
            !online && networkish ->
                info("Нет интернета", "Телефон сейчас не в сети.",
                    "Включи Wi-Fi или мобильный интернет.", "Без сети играют скачанные треки — фильтр «Скачанные» в медиатеке.")
            chain.any { it is AuthRequiredException } || "401" in lower ->
                info("Нужно войти заново", "Сервис не принял сохранённый вход — токен устарел или был отозван.",
                    "Сервисы → Яндекс.Музыка → Выйти, затем снова «Войти».")
            chain.any { it is UnknownHostException } || "unable to resolve host" in lower ->
                info("Нет интернета или сервер недоступен", "Телефон не смог найти адрес сервера.",
                    "Проверь, что есть интернет.",
                    if (youtube || spotify) "YouTube и Spotify в России часто доступны только через VPN — включи его или укажи прокси в Настройки → Сеть." else "Если включён VPN — попробуй выключить его или сменить сервер.")
            chain.any { it is SocketTimeoutException || it is ConnectException } || "timeout" in lower || "network is unreachable" in lower || "failed to connect" in lower ->
                info("Сервер не отвечает", "Соединение не установилось вовремя.",
                    "Проверь интернет и попробуй ещё раз.",
                    if (youtube || spotify) "Похоже на блокировку: включи VPN или прокси (Настройки → Сеть)." else "Если используешь VPN или прокси — проверь, что они работают.")
            chain.any { it is SSLException } ->
                info("Не удалось установить защищённое соединение", "Обычно это вмешательство сети: провайдер, VPN или прокси.",
                    "Попробуй другой VPN-сервер или выключи прокси.", "Проверь, что на телефоне правильные дата и время.")
            "429" in lower || "recaptcha" in lower || "too many" in lower ->
                info("Слишком много запросов", "Сервис временно ограничил доступ.",
                    "Подожди несколько минут.", if (youtube) "Смена VPN-сервера обычно помогает сразу." else "Не запускай много загрузок одновременно.")
            "403" in lower ->
                info("Сервис отказал в доступе", if (youtube) "YouTube не отдал этот поток (ограничение региона или защита от ботов)." else "Трек недоступен для твоего аккаунта или региона.",
                    if (youtube) "Попробуй ещё раз или через VPN — ссылки YouTube иногда отклоняются случайно." else "Для lossless и части треков в Яндексе нужна подписка Плюс.",
                    "Если повторяется — пришли подробности.")
            "404" in lower || chain.any { it is FileNotFoundException && "/storage" !in (it.message ?: "") } ->
                info("Не найдено", "Трека или страницы больше нет на сервисе.", "Возможно, его удалили или перезалили — попробуй найти поиском.")
            "enospc" in lower || "no space" in lower ->
                info("Закончилось место", "На телефоне нет свободного места для файла.", "Освободи место или выбери другую папку загрузок в настройках.")
            "eacces" in lower || "permission denied" in lower || chain.any { it is SecurityException } ->
                info("Нет доступа к файлам", "Android не дал приложению записать или прочитать файл.",
                    "Настройки Android → Приложения → Palimpsest → Разрешения → «Все файлы».")
            "cannotwrite" in lower || "cannotread" in lower || "jaudiotagger" in lower ->
                info("Не получилось прочитать или записать теги", "Файл повреждён, занят другим приложением или его формат не поддерживает теги.",
                    "Попробуй ещё раз, закрыв другие плееры.", "Формат WebM теги не поддерживает — скачай трек заново как M4A.")
            playback != null && playback.errorCode in PlaybackException.ERROR_CODE_DECODING_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                info("Файл не воспроизводится", "Телефон не умеет декодировать этот формат или файл повреждён.", "Проверь файл в другом плеере.", "Если это загрузка — скачай трек заново.")
            playback != null && playback.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                info("Трек не загрузился", "Не удалось получить звук — нет сети или сервис не отдал поток.",
                    "Проверь интернет; без сети играют только скачанные треки (фильтр «Скачанные»).",
                    if (youtube) "Для YouTube может понадобиться VPN." else "Скачай трек, чтобы слушать его офлайн.")
            else -> info("Что-то пошло не так", t.message?.take(200) ?: t.javaClass.simpleName, "Попробуй ещё раз.", "Если повторяется — скопируй подробности и пришли их.")
        }
    }

    private fun details(t: Throwable, context: String?): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().lineSequence().take(40).joinToString("\n")
        return buildString {
            appendLine("Palimpsest ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
            context?.let { appendLine("Где: $it") }
            appendLine()
            append(trace)
        }
    }
}

/**
 * Collects errors from everywhere (playback, downloads, sync, search…), keeps a journal on
 * disk for Settings → «Журнал ошибок», and asks the UI to show the ones worth showing.
 */
class ErrorCenter(private val context: Context) {
    private val file = File(context.filesDir, "errors.log")
    private val _log = MutableStateFlow(load())
    val log: StateFlow<List<ErrorInfo>> = _log

    private val _shown = MutableSharedFlow<ErrorInfo>(extraBufferCapacity = 8)
    /** Errors the UI should surface (as a snackbar with «Подробнее», or straight away). */
    val shown: SharedFlow<ErrorInfo> = _shown

    fun report(t: Throwable, context: String? = null, show: Boolean = true): ErrorInfo {
        val info = ErrorExplainer.explain(t, context, online())
        _log.update { (listOf(info) + it).take(MAX) }
        runCatching { persist() }
        if (show) _shown.tryEmit(info)
        return info
    }

    private fun online(): Boolean = runCatching {
        val cm = this.context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    fun clear() {
        _log.value = emptyList()
        file.delete()
    }

    private fun persist() {
        file.writeText(_log.value.joinToString(SEP) { listOf(it.at, it.title, it.explanation, it.hints.joinToString("\u0001"), it.context.orEmpty(), it.details).joinToString(FIELD) })
    }

    private fun load(): List<ErrorInfo> = runCatching {
        if (!file.isFile) return emptyList()
        file.readText().split(SEP).mapNotNull { rec ->
            val f = rec.split(FIELD)
            if (f.size < 6) null else ErrorInfo(f[1], f[2], f[3].split("\u0001").filter { it.isNotEmpty() }, f[5], f[0].toLongOrNull() ?: 0, f[4].ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX = 100
        private const val SEP = "\u0002\n"
        private const val FIELD = "\u0003"
    }
}

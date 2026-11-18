package com.example.alisazello

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.app.SearchManager
import android.view.KeyEvent

/**
 * Best-effort control of the user's installed Yandex Music client.
 * Track search uses Android's standard play-from-search contract, explicitly addressed
 * to the official Yandex Music package. Actual support is verified on the target phone.
 */
internal object YandexMusicController {
    private const val PACKAGE = "ru.yandex.music"

    fun handle(context: Context, command: String): String {
        val text = command.trim()
        val lower = text.lowercase()
        return when {
            Regex("(?i)\\b(следующ|дальше|следующий трек)\\b").containsMatchIn(text) -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                "Передала Яндекс Музыке команду «следующий трек»."
            }
            Regex("(?i)\\b(предыдущ|назад)\\b").containsMatchIn(text) -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Передала команду вернуться к предыдущему треку."
            }
            Regex("(?i)\\b(пауза|останови|выключи музыку|стоп музыка)\\b").containsMatchIn(text) -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
                "Отправила Яндекс Музыке команду паузы."
            }
            Regex("(?i)\\b(продолжи|возобнови|играй дальше)\\b").containsMatchIn(text) -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                "Отправила команду продолжить воспроизведение."
            }
            Regex("(?i)\\b(громче|увеличь громкость)\\b").containsMatchIn(text) -> {
                adjustVolume(context, AudioManager.ADJUST_RAISE)
                "Сделала громче системную громкость мультимедиа."
            }
            Regex("(?i)\\b(тише|уменьши громкость)\\b").containsMatchIn(text) -> {
                adjustVolume(context, AudioManager.ADJUST_LOWER)
                "Сделала тише системную громкость мультимедиа."
            }
            else -> {
                val query = text.replace(Regex("(?i)^(пожалуйста[, ]*)?(включи|поставь|запусти|найди и включи|сыграй|воспроизведи)\\s*"), "").trim()
                if (query.isBlank()) return "Скажи название песни или исполнителя."
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage(PACKAGE)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val canHandle = intent.resolveActivity(context.packageManager) != null
                if (canHandle) {
                    context.startActivity(intent)
                    "Отправила в установленную Яндекс Музыку запрос: $query. Если приложение поддерживает поиск по голосовой команде, оно начнёт воспроизведение."
                } else {
                    val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        "Открыла Яндекс Музыку, но её версия не объявила поддержку системного поиска по песням. Самостоятельно выбрать трек из каталога пока не могу."
                    } else {
                        "Не нашла установленное приложение Яндекс Музыка на этом телефоне."
                    }
                }
            }
        }
    }

    private fun sendMediaKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun adjustVolume(context: Context, direction: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }
}

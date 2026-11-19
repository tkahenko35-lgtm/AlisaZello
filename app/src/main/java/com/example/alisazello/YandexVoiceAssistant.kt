package com.example.alisazello

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.zello.channel.sdk.IncomingVoiceConfiguration
import com.zello.channel.sdk.IncomingVoiceStream
import com.zello.channel.sdk.OutgoingVoiceConfiguration
import com.zello.channel.sdk.OutgoingVoiceStream
import com.zello.channel.sdk.Session
import com.zello.channel.sdk.VoiceReceiver
import com.zello.channel.sdk.VoiceSink
import com.zello.channel.sdk.VoiceSource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Speech pipeline for Zello channel audio:
 * incoming Zello PCM -> Yandex SpeechKit STT -> YandexGPT -> SpeechKit TTS -> Zello PCM.
 * It never opens or records the phone microphone.
 */
internal object YandexVoiceAssistant {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val TARGET_RATE = 16000
    private val replyQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<Session, ShortArray>>()
    @Volatile private var replyQueueRunning = false

    fun incomingConfiguration(onAudio: (String, ShortArray, Int) -> Unit): IncomingVoiceConfiguration {
        val receiver = object : VoiceReceiver {
            private val buffer = ByteArrayOutputStream()
            @Volatile private var senderName = "участник"
            @Volatile private var rate = TARGET_RATE

            override fun prepare(stream: IncomingVoiceStream, sampleRate: Int) {
                senderName = stream.sender
                rate = sampleRate
            }

            override fun receive(audio: ShortArray, stream: IncomingVoiceStream) {
                synchronized(buffer) {
                    val bytes = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    audio.forEach { bytes.putShort(it) }
                    buffer.write(bytes.array())
                }
            }

            override fun onStreamStopped(stream: IncomingVoiceStream) {
                val bytes = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
                if (bytes.isEmpty()) return
                val samples = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                onAudio(senderName, samples, rate)
            }
        }
        return IncomingVoiceConfiguration(receiver, true)
    }

    fun processRequest(
        context: Context,
        apiKey: String,
        folderId: String,
        sender: String,
        pcm: ShortArray,
        sampleRate: Int,
        onReply: (String, ShortArray?) -> Unit
    ) {
        worker.execute {
            try {
                val normalized = resample(pcm, sampleRate, TARGET_RATE)
                val recognized = recognize(apiKey, folderId, normalized)
                if (recognized.isBlank() || !recognized.contains(Regex("алис[аы]", RegexOption.IGNORE_CASE))) return@execute
                val question = recognized.replace(Regex("(?i)алис[аы][,!. ]*"), "").trim()
                if (question.isBlank()) {
                    val phrase = "Я здесь. Говори, не стесняйся."
                    onReply(phrase, synthesize(apiKey, folderId, phrase))
                    return@execute
                }
                val answer = if (isMusicCommand(question)) {
                    YandexMusicController.handle(context, question)
                } else {
                    ask(apiKey, folderId, sender, question)
                }
                onReply(answer, synthesize(apiKey, folderId, answer))
            } catch (e: Exception) {
                onReply("Не смогла обработать запрос Яндекса: ${safeError(e)}", null)
            }
        }
    }

    /** Queue replies; start/stop stream calls are marshalled to the UI thread. */
    fun speakInChannel(session: Session, audio: ShortArray) {
        if (audio.isEmpty()) return
        replyQueue.add(session to audio.copyOf())
        main.post { drainReplyQueue() }
    }

    private fun drainReplyQueue() {
        if (replyQueueRunning) return
        val item = replyQueue.poll() ?: return
        replyQueueRunning = true
        val (session, audio) = item
        val source = object : VoiceSource {
            @Volatile private var cancelled = false
            override fun startProvidingAudio(sink: VoiceSink, sampleRate: Int, stream: OutgoingVoiceStream) {
                worker.execute {
                    try {
                        var offset = 0
                        while (!cancelled && offset < audio.size) {
                            val end = minOf(offset + 320, audio.size)
                            sink.provideAudio(audio.copyOfRange(offset, end))
                            offset = end
                            Thread.sleep(20)
                        }
                    } catch (_: Exception) {
                    } finally {
                        main.post {
                            runCatching { stream.stop() }
                            finishReplyQueue()
                        }
                    }
                }
            }
            override fun stopProvidingAudio(sink: VoiceSink) { cancelled = true }
        }
        val stream = runCatching {
            session.startVoiceMessage(OutgoingVoiceConfiguration(source, TARGET_RATE))
        }.getOrNull()
        if (stream == null) {
            // SDK rejected/failed to create the outgoing stream; don't pretend it started.
            main.postDelayed({ finishReplyQueue() }, 1800L)
        }
    }

    private fun finishReplyQueue() {
        replyQueueRunning = false
        main.postDelayed({ drainReplyQueue() }, 1200L)
    }

    private fun recognize(apiKey: String, folderId: String, samples: ShortArray): String {
        val audio = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { audio.putShort(it) }
        val url = "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize" +
            "?lang=ru-RU&topic=general&format=lpcm&sampleRateHertz=16000&folderId=" + enc(folderId)
        val response = request(url, apiKey, "application/octet-stream", audio.array())
        return JSONObject(String(response, StandardCharsets.UTF_8)).optString("result")
    }

    private fun ask(apiKey: String, folderId: String, sender: String, question: String): String {
        val body = JSONObject()
            .put("modelUri", "gpt://$folderId/yandexgpt/latest")
            .put("completionOptions", JSONObject().put("stream", false).put("temperature", 0.65).put("maxTokens", 180))
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("text",
                    "Ты Алиса в Zello, образ Радио Хулиганка: дерзкая, остроумная, разговорная, можешь использовать умеренный мат и подколы без угроз и травли. Отвечай по-русски коротко, чтобы ответ удобно было слушать по рации."))
                .put(JSONObject().put("role", "user").put("text", "Говорит участник $sender. Запрос: $question")))
        val response = request(
            "https://llm.api.cloud.yandex.net/foundationModels/v1/completion",
            apiKey,
            "application/json; charset=utf-8",
            body.toString().toByteArray(StandardCharsets.UTF_8)
        )
        val json = JSONObject(String(response, StandardCharsets.UTF_8))
        val alternatives = json.optJSONObject("result")?.optJSONArray("alternatives")
        val text = alternatives?.optJSONObject(0)?.optJSONObject("message")?.optString("text").orEmpty()
        return text.ifBlank { "Что-то я зависла. Повтори вопрос." }.take(900)
    }

    private fun synthesize(apiKey: String, folderId: String, text: String): ShortArray {
        val form = "text=${enc(text)}&lang=ru-RU&voice=alena&folderId=${enc(folderId)}&format=lpcm&sampleRateHertz=16000"
        val bytes = request(
            "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize",
            apiKey,
            "application/x-www-form-urlencoded; charset=utf-8",
            form.toByteArray(StandardCharsets.UTF_8)
        )
        val evenLength = bytes.size - (bytes.size % 2)
        val samples = ShortArray(evenLength / 2)
        ByteBuffer.wrap(bytes, 0, evenLength).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }

    private fun request(url: String, apiKey: String, contentType: String, body: ByteArray): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Api-Key $apiKey")
            setRequestProperty("Content-Type", contentType)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(String(data, StandardCharsets.UTF_8)).optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $code ${detail?.take(180).orEmpty()}")
            }
            data
        } finally {
            connection.disconnect()
        }
    }

    private fun resample(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        if (sourceRate <= 0 || sourceRate == targetRate || input.isEmpty()) return input
        val outputSize = (input.size.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        return ShortArray(outputSize) { i ->
            val position = i.toDouble() * sourceRate / targetRate
            val left = position.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = position - left
            (input[left] * (1.0 - fraction) + input[right] * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun isMusicCommand(text: String): Boolean =
        Regex("(?i)(включи|поставь|запусти|сыграй|воспроизведи|найди и включи|следующ|предыдущ|дальше|назад|пауза|останови|стоп музыка|выключи музыку|продолжи|возобнови|играй дальше|громче|тише)").containsMatchIn(text)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun safeError(error: Exception): String =
        (error.message ?: error.javaClass.simpleName).replace(Regex("(Api-Key\\s+)\\S+"), "$1***").take(180)
}

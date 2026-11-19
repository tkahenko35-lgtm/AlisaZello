package com.example.alisazello

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Speech pipeline for Zello channel audio:
 * incoming Zello PCM -> Yandex SpeechKit STT -> YandexGPT -> SpeechKit TTS -> Zello PCM.
 * It never opens or records the phone microphone.
 */
internal object YandexVoiceAssistant {
    // Network requests and outgoing audio must not share one worker: a long Zello
    // voice transmission must never block recognition of the next channel request.
    private val requestWorker = Executors.newSingleThreadExecutor()
    // Serialize outgoing voice streams to avoid overlapping VoiceSource transmissions.
    private val audioWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val TARGET_RATE = 16000
    // After a user says "Виталик", keep that user's dialogue open for two minutes.
    // Each new question refreshes the window; other channel users have separate sessions.
    private const val DIALOG_WINDOW_MS = 600_000L
    private val activeDialogs = ConcurrentHashMap<String, Long>()
    // Short per-speaker conversation memory so Vitalik can refer to earlier turns.
    private val dialogHistory = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    fun incomingConfiguration(playThroughSpeaker: Boolean = false, onAudio: (String, ShortArray, Int) -> Unit): IncomingVoiceConfiguration {
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
                Log.d("AlisaRadio", "Incoming voice stopped: sender=$senderName samples=${samples.size} rate=$rate")
                onAudio(senderName, samples, rate)
            }
        }
        return IncomingVoiceConfiguration(receiver, playThroughSpeaker)
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
        requestWorker.execute {
            Log.d("AlisaRadio", "Processing request from $sender, samples=${pcm.size}, rate=$sampleRate")
            try {
                val normalized = resample(pcm, sampleRate, TARGET_RATE)
                val recognized = recognize(apiKey, folderId, normalized)
                val cleanText = recognized.trim()
                val hasWakeWord = cleanText.contains(Regex("виталик", RegexOption.IGNORE_CASE))
                val now = System.currentTimeMillis()
                val dialogActive = (activeDialogs[sender] ?: 0L) > now
                Log.d("AlisaRadio", "Speech recognized: chars=${cleanText.length}, wakeWord=$hasWakeWord, dialogActive=$dialogActive")
                if (cleanText.isBlank() || (!hasWakeWord && !dialogActive)) return@execute

                // Wake word starts a dialogue; follow-up questions from the same
                // participant are accepted without repeating "Виталик".
                activeDialogs[sender] = now + DIALOG_WINDOW_MS
                val question = cleanText.replace(Regex("(?i)виталик[,!. ]*"), "").trim()
                if (question.isBlank()) {
                    val phrase = "Я здесь. Говори, не стесняйся."
                    onReply(phrase, synthesize(apiKey, folderId, phrase))
                    return@execute
                }
                val answer = when {
                    InfoCommandController.isWeatherCommand(question) ->
                        InfoCommandController.weather(question)
                    InfoCommandController.isNewsCommand(question) ->
                        InfoCommandController.news(question)
                    else -> ask(apiKey, folderId, sender, question)
                }
                val replyAudio = synthesize(apiKey, folderId, answer)
                Log.d("AlisaRadio", "Reply synthesized: chars=${answer.length}, samples=${replyAudio.size}")
                onReply(answer, replyAudio)
            } catch (e: Exception) {
                Log.e("AlisaRadio", "Request processing failed", e)
                val message = "Не смог обработать запрос: ${safeError(e)}"
                val fallbackAudio = runCatching { synthesize(apiKey, folderId, message) }.getOrNull()
                onReply(message, fallbackAudio)
            }
        }
    }

    // Queue outgoing Zello messages. Starting a second VoiceSource before the SDK
    // has stopped the previous stream can leave the channel unable to transmit again.
    private val replyQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<Session, ShortArray>>()
    @Volatile private var replyQueueRunning = false

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
                audioWorker.execute {
                    try {
                        var offset = 0
                        while (!cancelled && offset < audio.size) {
                            val next = minOf(offset + 320, audio.size)
                            sink.provideAudio(audio.copyOfRange(offset, next))
                            offset = next
                            Thread.sleep(20)
                        }
                    } catch (e: Exception) {
                        Log.w("AlisaRadio", "Outgoing audio stream ended with error", e)
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
            Log.e("AlisaRadio", "Zello rejected outgoing voice stream")
            main.postDelayed({ finishReplyQueue() }, 1800L)
        }
    }

    private fun finishReplyQueue() {
        if (!replyQueueRunning) return
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
        val history = dialogHistory.getOrPut(sender) { mutableListOf() }
        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "system").put("text",
                "Ты Виталик — развязный, расхлябанный радиохулиган, будто слегка навеселе: мужицкая хрипотца, наглая уверенность, дворовый юмор, крепкий русский мат, едкий сарказм и меткие подколы. Не стесняйся материться к месту, можешь дружески обозвать собеседника, поспорить, огрызнуться и жёстко пошутить. Не скатывайся в однообразный поток ругани: придумывай свежие шутки и отвечай по существу. Важная фишка: если сам выдал удачную шутку, можешь довольно расхохотаться прямо после неё — например «Ха-ха-ха!», «АХА! Ну я выдал!» или «Гы-гы-гы!». Смейся естественно и иногда, а не после каждой фразы; иногда можешь сначала фыркнуть или хохотнуть, а потом продолжить мысль. Поддерживай настоящую беседу: помни предыдущие реплики, учитывай контекст, задавай встречные вопросы, подхватывай темы и не отвечай как справочник. После первого обращения не требуй повторять имя «Виталик». Не представляйся заново и не начинай каждый ответ одинаково. Для рации говори короткими, ясными фразами. Не угрожай и не унижай людей по признакам их принадлежности к защищённым группам."))
        synchronized(history) {
            history.takeLast(12).forEach { (userText, assistantText) ->
                messages.put(JSONObject().put("role", "user").put("text", userText))
                messages.put(JSONObject().put("role", "assistant").put("text", assistantText))
            }
            messages.put(JSONObject().put("role", "user").put("text", "Говорит участник $sender. Запрос: $question"))
        }
        val body = JSONObject()
            .put("modelUri", "gpt://$folderId/yandexgpt/latest")
            .put("completionOptions", JSONObject().put("stream", false).put("temperature", 0.85).put("maxTokens", 220))
            .put("messages", messages)
        val response = request(
            "https://llm.api.cloud.yandex.net/foundationModels/v1/completion",
            apiKey,
            "application/json; charset=utf-8",
            body.toString().toByteArray(StandardCharsets.UTF_8)
        )
        val json = JSONObject(String(response, StandardCharsets.UTF_8))
        val alternatives = json.optJSONObject("result")?.optJSONArray("alternatives")
        val answer = alternatives?.optJSONObject(0)?.optJSONObject("message")?.optString("text").orEmpty()
            .ifBlank { "Чё-то я завис. Повтори вопрос." }.take(900)
        synchronized(history) {
            history.add(question to answer)
            while (history.size > 12) history.removeAt(0)
        }
        return answer
    }

    private fun synthesize(apiKey: String, folderId: String, text: String): ShortArray {
        val form = "text=${enc(text)}&lang=ru-RU&voice=ermil&emotion=good&folderId=${enc(folderId)}&format=lpcm&sampleRateHertz=16000"
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


    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun safeError(error: Exception): String =
        (error.message ?: error.javaClass.simpleName).replace(Regex("(Api-Key\\s+)\\S+"), "$1***").take(180)
}

package com.example.alisazello

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.zello.channel.sdk.OutgoingVoiceConfiguration
import com.zello.channel.sdk.OutgoingVoiceStream
import com.zello.channel.sdk.Session
import com.zello.channel.sdk.VoiceReceiver
import com.zello.channel.sdk.VoiceSink
import com.zello.channel.sdk.VoiceSource
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Internet radio decoder. PCM is captured from Media3 and sent only to Zello; local volume stays muted. */
@OptIn(UnstableApi::class)
internal object RadioStreamController {
    private const val TAG = "VitalikRadio"
    private const val RATE = 16000
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val frames = ArrayBlockingQueue<ShortArray>(100)
    @Volatile private var player: ExoPlayer? = null
    @Volatile private var voiceStream: OutgoingVoiceStream? = null
    @Volatile private var running = false
    @Volatile private var selectedStation = ""
    @Volatile private var lastStationUuid = ""
    private var pendingStart: Runnable? = null

    fun isRadioCommand(text: String): Boolean = Regex("(?i)(радио|станци|включи музыку|поставь музыку|играй музыку|следующ.*станц|останови музыку|выключи музыку|стоп музыка)").containsMatchIn(text)
    fun isStopCommand(text: String): Boolean = Regex("(?i)(стоп радио|выключи радио|останови радио|останови музыку|выключи музыку|стоп музыка)").containsMatchIn(text)

    fun handle(context: Context, session: Session, command: String): String {
        if (isStopCommand(command)) { stop(); return "Ладно, радио выключено." }
        val query = command.replace(Regex("(?i)(включи|поставь|запусти|сыграй|найди и включи|следующ\\w*\\s+станци\\w*|радио|музыку|станцию|станция|пожалуйста)"), " ").trim()
        val genre = when (query.lowercase()) {
            "рок" -> "rock"
            "поп", "попса" -> "pop"
            "джаз" -> "jazz"
            "классика", "классическая" -> "classical"
            "шансон" -> "chanson"
            "электронная", "электроника" -> "electronic"
            "танцевальная" -> "dance"
            "новости", "информационное" -> "news"
            else -> ""
        }
        val params = if (query.isBlank()) "countrycode=RU" else if (genre.isNotBlank()) "tag=" + enc(genre) else "name=" + enc(query)
        val endpoint = "https://de1.api.radio-browser.info/json/stations/search?" + params + "&hidebroken=true&order=clickcount&reverse=true&limit=30"
        val stations = JSONArray(String(get(endpoint), Charsets.UTF_8))
        if (stations.length() == 0) return "Не нашёл подходящую радиостанцию. Назови жанр или станцию."
        var chosen: org.json.JSONObject? = null
        for (i in 0 until stations.length()) {
            val candidate = stations.optJSONObject(i) ?: continue
            val stream = candidate.optString("url_resolved").ifBlank { candidate.optString("url") }
            val stationId = candidate.optString("stationuuid")
            if (stream.startsWith("https://") && stationId != lastStationUuid) { chosen = candidate; break }
        }
        val streamUrl = chosen?.optString("url_resolved").orEmpty().ifBlank { chosen?.optString("url").orEmpty() }
        if (streamUrl.isBlank()) return "У этой станции нет доступного потока. Попробуй другую."
        val stationName = chosen?.optString("name").orEmpty().ifBlank { "Интернет-радио" }
        lastStationUuid = chosen?.optString("stationuuid").orEmpty()
        stop()
        selectedStation = stationName
        val start = Runnable {
            pendingStart = null
            startTransmission(context, session, streamUrl)
        }
        pendingStart = start
        main.postDelayed(start, 4500L)
        return "Нашёл станцию «" + stationName + "». Сейчас включу её в канал Zello."
    }

    fun stop() {
        running = false
        pendingStart?.let(main::removeCallbacks)
        pendingStart = null
        main.post {
            runCatching { voiceStream?.stop() }
            voiceStream = null
            player?.release()
            player = null
            frames.clear()
        }
    }

    private fun startTransmission(context: Context, session: Session, streamUrl: String) {
        if (player != null) return
        frames.clear()
        running = true
        val processor = RadioPcmProcessor { bytes, sampleRate, channels -> enqueuePcm(bytes, sampleRate, channels) }
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }
        val exo = ExoPlayer.Builder(context, renderers).build()
        player = exo
        exo.volume = 0f
        exo.setMediaItem(MediaItem.fromUri(streamUrl))
        exo.prepare()
        exo.play()
        val source = object : VoiceSource {
            @Volatile private var cancelled = false
            override fun startProvidingAudio(sink: VoiceSink, sampleRate: Int, stream: OutgoingVoiceStream) {
                voiceStream = stream
                worker.execute {
                    try {
                        while (running && !cancelled) {
                            val chunk = frames.poll(250, TimeUnit.MILLISECONDS)
                            if (chunk != null) sink.provideAudio(chunk)
                            else sink.provideAudio(ShortArray(320))
                        }
                    } catch (e: Exception) { Log.w(TAG, "Radio voice stream stopped", e) }
                    finally { main.post { if (!running) { runCatching { stream.stop() }; voiceStream = null } } }
                }
            }
            override fun stopProvidingAudio(sink: VoiceSink) { cancelled = true; running = false }
        }
        val started = runCatching { session.startVoiceMessage(OutgoingVoiceConfiguration(source, RATE)) }.getOrNull()
        if (started == null) {
            running = false
            exo.release()
            player = null
            Log.e(TAG, "Zello rejected radio voice stream")
        } else { voiceStream = started; Log.i(TAG, "Radio started: " + selectedStation) }
    }

    private fun enqueuePcm(bytes: ByteArray, sampleRate: Int, channels: Int) {
        if (!running || sampleRate <= 0 || channels <= 0) return
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inputFrames = input.remaining() / channels
        val outputCount = (inputFrames.toLong() * RATE / sampleRate).toInt()
        val output = ShortArray(outputCount)
        for (i in output.indices) {
            val frame = (i.toLong() * sampleRate / RATE).toInt().coerceAtMost(inputFrames - 1)
            var sum = 0
            for (channel in 0 until channels) sum += input.get(frame * channels + channel).toInt()
            output[i] = (sum / channels).toShort()
        }
        var offset = 0
        while (offset < output.size) {
            val end = minOf(offset + 320, output.size)
            val chunk = ShortArray(320)
            output.copyInto(chunk, 0, offset, end)
            if (!frames.offer(chunk)) { frames.poll(); frames.offer(chunk) }
            offset = end
        }
    }

    private class RadioPcmProcessor(private val onPcm: (ByteArray, Int, Int) -> Unit) : BaseAudioProcessor() {
        private var format = AudioProcessor.AudioFormat.NOT_SET
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            format = inputAudioFormat
            return inputAudioFormat
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            val count = inputBuffer.remaining()
            val copy = ByteArray(count)
            inputBuffer.get(copy)
            onPcm(copy, format.sampleRate, format.channelCount)
            replaceOutputBuffer(count).put(copy).flip()
        }
        override fun onFlush() { }
        override fun onReset() { format = AudioProcessor.AudioFormat.NOT_SET }
    }

    private fun get(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000; setRequestProperty("User-Agent", "VitalikZello/1.0")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) throw IllegalStateException("Radio directory HTTP " + status)
            bytes
        } finally { connection.disconnect() }
    }
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

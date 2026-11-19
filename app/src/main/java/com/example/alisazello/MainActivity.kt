package com.example.alisazello

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zello.channel.sdk.*
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Background = Color(0xFF101522)
private val Panel = Color(0xFF1B2434)
private val Accent = Color(0xFFFFB547)
private val AccentSoft = Color(0xFF3A2B1C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlisaApp() }
    }
}

/** Encrypts the developer token with a key held by Android Keystore. */
private object SecureCredentialStore {
    private const val PREFS = "alisa_secure_credentials"
    private const val KEY_ALIAS = "alisa_zello_credentials_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    fun save(context: android.content.Context, username: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal((username + "\n" + password).toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, 0).edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
    fun read(context: android.content.Context): Pair<String, String>? = runCatching {
        val p = context.getSharedPreferences(PREFS, 0); val iv = p.getString("iv", null) ?: return null; val data = p.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val decoded = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8); val i = decoded.indexOf('\n')
        if (i <= 0) null else Pair(decoded.substring(0, i), decoded.substring(i + 1))
    }.getOrNull()
    fun clear(context: android.content.Context) { context.getSharedPreferences(PREFS, 0).edit().clear().apply() }
}

/** Stores Yandex AI Studio credentials encrypted with Android Keystore. */
private object YandexCredentialStore {
    private const val PREFS = "alisa_yandex_secure"
    private const val KEY_ALIAS = "alisa_yandex_credentials_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    fun save(context: android.content.Context, apiKey: String, folderId: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal((apiKey.trim() + "\n" + folderId.trim()).toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    fun read(context: android.content.Context): Pair<String, String>? = runCatching {
        val p = context.getSharedPreferences(PREFS, 0)
        val iv = Base64.decode(p.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val data = Base64.decode(p.getString("data", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val value = String(cipher.doFinal(data), StandardCharsets.UTF_8)
        val split = value.indexOf('\n')
        if (split <= 0) null else Pair(value.substring(0, split), value.substring(split + 1))
    }.getOrNull()
    fun clear(context: android.content.Context) {
        context.getSharedPreferences(PREFS, 0).edit().clear().apply()
    }
}

private object SecureTokenStore {
    private const val PREFS = "alisa_secure"
    private const val KEY_ALIAS = "alisa_zello_token_key"
    private const val IV_KEY = "token_iv"
    private const val DATA_KEY = "token_data"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: android.content.Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun read(context: android.content.Context): String {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, 0)
            val iv = Base64.decode(prefs.getString(IV_KEY, null), Base64.NO_WRAP)
            val data = Base64.decode(prefs.getString(DATA_KEY, null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    fun clear(context: android.content.Context) {
        context.getSharedPreferences(PREFS, 0).edit().clear().apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlisaApp() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("alisa_radio", 0) }
    var assistantEnabled by remember { mutableStateOf(false) }
    var playIncomingAudioInVitalik by remember { mutableStateOf(preferences.getBoolean("play_incoming_audio_in_vitalik", false)) }
    var newChannel by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(SecureTokenStore.read(context)) }
    var tokenSaved by remember { mutableStateOf(tokenInput.isNotBlank()) }
    var zelloUsername by remember { mutableStateOf(SecureCredentialStore.read(context)?.first.orEmpty()) }
    var zelloPassword by remember { mutableStateOf(SecureCredentialStore.read(context)?.second.orEmpty()) }
    var credentialsSaved by remember { mutableStateOf(SecureCredentialStore.read(context) != null) }
    var yandexApiKey by remember { mutableStateOf(YandexCredentialStore.read(context)?.first.orEmpty()) }
    var yandexFolderId by remember { mutableStateOf(YandexCredentialStore.read(context)?.second.orEmpty()) }
    var yandexSaved by remember { mutableStateOf(YandexCredentialStore.read(context) != null) }
    var connectionStatus by remember { mutableStateOf("Не подключено") }
    var connectedChannel by remember { mutableStateOf<String?>(null) }
    var zelloSession by remember { mutableStateOf<Session?>(null) }
    val reconnectHandler = remember { Handler(Looper.getMainLooper()) }
    var reconnectRunnable by remember { mutableStateOf<Runnable?>(null) }
    var reconnectAttempt by remember { mutableIntStateOf(0) }

    val channels = remember(context) {
        mutableStateListOf<String>().apply {
            val saved = preferences.getStringSet("channels", null)
            addAll(saved ?: emptySet())
            listOf("Умные нелюди", "~Мы Семья~", "Перелом").forEach { channel ->
                if (!contains(channel)) add(channel)
            }
        }
    }
    LaunchedEffect(channels.toList()) {
        preferences.edit().putStringSet("channels", LinkedHashSet(channels)).apply()
    }

    DisposableEffect(Unit) {
        onDispose {
            reconnectRunnable?.let(reconnectHandler::removeCallbacks)
            reconnectRunnable = null
            zelloSession?.disconnect()
        }
    }

    fun disconnectZello(resetRetry: Boolean = true) {
        reconnectRunnable?.let(reconnectHandler::removeCallbacks)
        reconnectRunnable = null
        zelloSession?.disconnect()
        zelloSession = null
        connectedChannel = null
        if (resetRetry) reconnectAttempt = 0
        connectionStatus = "Не подключено"
    }

    fun connectZello(channel: String, isAutomaticRetry: Boolean = false) {
        val token = SecureTokenStore.read(context)
        if (token.isBlank()) {
            connectionStatus = "Сначала сохрани токен Zello Developer"
            return
        }
        disconnectZello(resetRetry = !isAutomaticRetry)
        connectionStatus = "Подключаемся к «$channel»…"
        connectedChannel = channel
        val credentials = SecureCredentialStore.read(context)
        val builder = Session.Builder(context, "wss://zello.io/ws", token, channel)
        if (credentials != null) builder.setUsername(credentials.first, credentials.second)
        val newSession = builder.build()
        newSession.sessionListener = object : SessionListener {
            override fun onConnectStarted(session: Session) {
                connectionStatus = "Устанавливаем соединение с «$channel»…"
            }
            override fun onConnectFailed(session: Session, error: SessionConnectError) {
                connectionStatus = when (error.code) {
                    SessionConnectError.Code.BAD_CREDENTIALS -> "Zello отклонил токен. Проверь его срок действия."
                    SessionConnectError.Code.CONNECT_FAILED -> "Не удалось открыть соединение. Проверь интернет."
                    SessionConnectError.Code.INVALID_ADDRESS -> "Неверный адрес сервера Zello."
                    SessionConnectError.Code.NO_RESPONSE -> "Zello не ответил вовремя. Попробуй ещё раз."
                    else -> "Ошибка подключения Zello: $error"
                }
                connectedChannel = null
                zelloSession = null
                if (error.code != SessionConnectError.Code.BAD_CREDENTIALS &&
                    error.code != SessionConnectError.Code.INVALID_ADDRESS) {
                    val delayMs = minOf(5_000L * (1L shl reconnectAttempt.coerceAtMost(4)), 60_000L)
                    reconnectAttempt += 1
                    connectionStatus = "Не удалось подключиться. Повторная попытка через ${delayMs / 1000} сек…"
                    val retry = Runnable {
                        reconnectRunnable = null
                        if (zelloSession == null && connectedChannel == null && tokenSaved) {
                            connectZello(channel, isAutomaticRetry = true)
                        }
                    }
                    reconnectRunnable?.let(reconnectHandler::removeCallbacks)
                    reconnectRunnable = retry
                    reconnectHandler.postDelayed(retry, delayMs)
                }
            }
            override fun onConnectSucceeded(session: Session) {
                if (zelloSession !== session) return
                reconnectRunnable?.let(reconnectHandler::removeCallbacks)
                reconnectRunnable = null
                reconnectAttempt = 0
                connectionStatus = "Подключено к «$channel»"
                connectedChannel = channel
            }
            override fun onDisconnected(session: Session) {
                if (zelloSession !== session) return
                connectedChannel = null
                zelloSession = null
                val delayMs = minOf(5_000L * (1L shl reconnectAttempt.coerceAtMost(4)), 60_000L)
                reconnectAttempt += 1
                connectionStatus = "Связь потеряна. Повторное подключение через ${delayMs / 1000} сек…"
                val retry = Runnable {
                    reconnectRunnable = null
                    if (zelloSession == null && connectedChannel == null && tokenSaved) {
                        connectZello(channel, isAutomaticRetry = true)
                    }
                }
                reconnectRunnable?.let(reconnectHandler::removeCallbacks)
                reconnectRunnable = retry
                reconnectHandler.postDelayed(retry, delayMs)
            }
            override fun onSessionWillReconnect(session: Session, reason: ReconnectReason): Boolean {
                connectionStatus = "Связь потеряна. Zello переподключается…"
                return true
            }
            override fun onOutgoingVoiceError(session: Session, stream: OutgoingVoiceStream, error: OutgoingVoiceStreamError) { Log.e("AlisaRadio", "Zello outgoing voice error: $error"); YandexVoiceAssistant.notifyOutgoingError() }
            override fun onOutgoingVoiceStateChanged(session: Session, stream: OutgoingVoiceStream) { Log.d("AlisaRadio", "Outgoing voice state changed") }
            override fun onOutgoingVoiceProgress(session: Session, stream: OutgoingVoiceStream, positionMs: Int) { if (positionMs % 5000 < 100) Log.d("AlisaRadio", "Outgoing voice progress: " + positionMs + "ms") }
            override fun onIncomingVoiceWillStart(session: Session, streamInfo: IncomingVoiceStreamInfo): IncomingVoiceConfiguration? {
                if (!assistantEnabled) return null
                val yandex = YandexCredentialStore.read(context) ?: return null
                val playLocally = preferences.getBoolean("play_incoming_audio_in_vitalik", false)
                return YandexVoiceAssistant.incomingConfiguration(playLocally) { sender, audio, sampleRate ->
                    YandexVoiceAssistant.processRequest(context, yandex.first, yandex.second, session, sender, audio, sampleRate) { _, replyAudio ->
                        if (replyAudio != null && assistantEnabled) {
                            YandexVoiceAssistant.speakInChannel(session, replyAudio)
                        }
                    }
                }
            }
            override fun onIncomingVoiceStarted(session: Session, stream: IncomingVoiceStream) { Log.d("AlisaRadio", "Incoming voice started: " + stream.sender) }
            override fun onIncomingVoiceStopped(session: Session, stream: IncomingVoiceStream) { Log.d("AlisaRadio", "Incoming voice stopped: " + stream.sender) }
            override fun onIncomingVoiceProgress(session: Session, stream: IncomingVoiceStream, positionMs: Int) { if (positionMs % 5000 < 100) Log.d("AlisaRadio", "Incoming voice progress: " + positionMs + "ms") }
            override fun onTextMessage(session: Session, sender: String, message: String) {}
            override fun onImageMessage(session: Session, imageInfo: ImageInfo) {}
            override fun onLocationMessage(session: Session, sender: String, location: Location) {}
        }
        zelloSession = newSession
        if (!newSession.connect()) {
            connectedChannel = null
            zelloSession = null
            val delayMs = minOf(5_000L * (1L shl reconnectAttempt.coerceAtMost(4)), 60_000L)
            reconnectAttempt += 1
            connectionStatus = "Не удалось запустить подключение. Повтор через ${delayMs / 1000} сек…"
            val retry = Runnable {
                reconnectRunnable = null
                if (zelloSession == null && connectedChannel == null && tokenSaved) {
                    connectZello(channel, isAutomaticRetry = true)
                }
            }
            reconnectRunnable?.let(reconnectHandler::removeCallbacks)
            reconnectRunnable = retry
            reconnectHandler.postDelayed(retry, delayMs)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Color(0xFFE47735), background = Background, surface = Panel)) {
        Scaffold(containerColor = Background, topBar = {
            TopAppBar(title = { Column {
                Text("ВИТАЛИК", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Text("РАДИОХУЛИГАН · ZELLO", fontSize = 11.sp, color = Accent)
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Background, titleContentColor = Color.White))
        }) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(88.dp).background(Accent.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(if (assistantEnabled) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = null, tint = Accent, modifier = Modifier.size(42.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(if (assistantEnabled) "Виталик включён" else "Виталик выключен", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(connectionStatus, color = Color.LightGray, fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { assistantEnabled = !assistantEnabled }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(if (assistantEnabled) "Выключить" else "Включить") }
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Воспроизводить входящий звук в Виталике", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("Выключено по умолчанию: звук слышен только через обычный Zello. Распознавание команд остаётся включённым.", color = Color.LightGray, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = playIncomingAudioInVitalik,
                                    onCheckedChange = {
                                        playIncomingAudioInVitalik = it
                                        preferences.edit().putBoolean("play_incoming_audio_in_vitalik", it).apply()
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Токен Zello Developer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(if (tokenSaved) "Токен сохранён на этом телефоне." else "Вставь сюда свой Sample Development Token. Сам токен никому не отправляй.", color = Color.LightGray, fontSize = 12.sp)
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it; tokenSaved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Токен разработчика") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val value = tokenInput.trim()
                                    if (value.isNotEmpty()) {
                                        runCatching { SecureTokenStore.save(context, value) }
                                            .onSuccess { tokenSaved = true }
                                            .onFailure { tokenSaved = false }
                                    }
                                }, modifier = Modifier.weight(1f), enabled = tokenInput.isNotBlank() && !tokenSaved) { Text(if (tokenSaved) "✓ Токен сохранён" else "Подтвердить токен") }
                                OutlinedButton(onClick = {
                                    SecureTokenStore.clear(context)
                                    tokenInput = ""
                                    tokenSaved = false
                                }) { Text("Очистить") }
                            }
                            Text("Токен хранится зашифрованным. Сохрани его, затем нажми «Подключить» у нужного канала. Без логина и пароля аккаунта доступно только прослушивание.", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Аккаунт Zello", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(if (credentialsSaved) "Данные аккаунта сохранены на телефоне." else "Для передачи голоса и музыки нужен вход в аккаунт. Без него доступно только прослушивание.", color = Color.LightGray, fontSize = 12.sp)
                            OutlinedTextField(value = zelloUsername, onValueChange = { zelloUsername = it; credentialsSaved = false }, modifier = Modifier.fillMaxWidth(), label = { Text("Имя пользователя Zello") }, singleLine = true)
                            OutlinedTextField(value = zelloPassword, onValueChange = { zelloPassword = it; credentialsSaved = false }, modifier = Modifier.fillMaxWidth(), label = { Text("Пароль Zello") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { val user = zelloUsername.trim(); if (user.isNotEmpty() && zelloPassword.isNotEmpty()) runCatching { SecureCredentialStore.save(context, user, zelloPassword) }.onSuccess { credentialsSaved = true }.onFailure { credentialsSaved = false } }, modifier = Modifier.weight(1f), enabled = zelloUsername.isNotBlank() && zelloPassword.isNotBlank() && !credentialsSaved) { Text(if (credentialsSaved) "✓ Вход сохранён" else "Подтвердить вход") }
                                OutlinedButton(onClick = { SecureCredentialStore.clear(context); zelloUsername = ""; zelloPassword = ""; credentialsSaved = false; if (connectedChannel != null) disconnectZello() }) { Text("Очистить") }
                            }
                            Text("Логин и пароль шифруются на телефоне. Не присылай их в чат. После сохранения переподключи канал.", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Yandex AI Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(if (yandexSaved) "Данные Яндекса сохранены на телефоне." else "API-ключ и ID каталога для распознавания речи, ответов YandexGPT и озвучивания Алисы.", color = Color.LightGray, fontSize = 12.sp)
                            OutlinedTextField(value = yandexApiKey, onValueChange = { yandexApiKey = it; yandexSaved = false }, modifier = Modifier.fillMaxWidth(), label = { Text("API-ключ Yandex AI Studio") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            OutlinedTextField(value = yandexFolderId, onValueChange = { yandexFolderId = it; yandexSaved = false }, modifier = Modifier.fillMaxWidth(), label = { Text("ID каталога Yandex Cloud") }, singleLine = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val key = yandexApiKey.trim()
                                    val folder = yandexFolderId.trim()
                                    if (key.isNotEmpty() && folder.isNotEmpty()) {
                                        runCatching { YandexCredentialStore.save(context, key, folder) }
                                            .onSuccess { yandexSaved = true }
                                            .onFailure { yandexSaved = false }
                                    }
                                }, modifier = Modifier.weight(1f), enabled = yandexApiKey.isNotBlank() && yandexFolderId.isNotBlank() && !yandexSaved) { Text(if (yandexSaved) "✓ Яндекс сохранён" else "Подтвердить Яндекс") }
                                OutlinedButton(onClick = {
                                    YandexCredentialStore.clear(context)
                                    yandexApiKey = ""
                                    yandexFolderId = ""
                                    yandexSaved = false
                                }) { Text("Очистить") }
                            }
                            Text("Ключ вводится только в приложение, не присылай его в чат. Сохранение защищено Android Keystore. Эти данные используются для распознавания речи, генерации ответов и озвучивания Виталика.", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }
                item { Text("Мои каналы", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                items(channels, key = { it }) { channel ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(channel, color = Color.White, fontWeight = FontWeight.Medium)
                                Text(if (connectedChannel == channel) connectionStatus else "Zello Friends & Family", color = Color.LightGray, fontSize = 12.sp)
                            }
                            Button(onClick = { connectZello(channel) }, enabled = tokenSaved && connectedChannel != channel) {
                                Text(if (connectedChannel == channel) "Подключено" else "Подключить")
                            }
                            TextButton(onClick = {
                                if (connectedChannel == channel) disconnectZello()
                                channels.remove(channel)
                            }) { Text("Удалить") }
                        }
                    }
                }
                item {
                    OutlinedTextField(value = newChannel, onValueChange = { newChannel = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Название канала Zello") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val name = newChannel.trim()
                        if (name.isNotEmpty() && !channels.contains(name)) { channels.add(name); newChannel = "" }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Добавить канал")
                    }
                }
                item { Text("Подключение без логина и пароля Zello работает только на прослушивание. Для передачи голоса и музыки потребуется авторизация аккаунта. Виталик работает через подключённый канал Zello. Новости, погода и интернет-радио доступны голосовыми командами.", color = Color.LightGray, fontSize = 12.sp) }
            }
        }
    }
}

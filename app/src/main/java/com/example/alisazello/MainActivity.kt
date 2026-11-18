package com.example.alisazello

import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
private val Accent = Color(0xFF9B8CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlisaApp() }
    }
}

/** Encrypts the developer token with a key held by Android Keystore. */
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
    var newChannel by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(SecureTokenStore.read(context)) }
    var tokenSaved by remember { mutableStateOf(tokenInput.isNotBlank()) }
    var connectionStatus by remember { mutableStateOf("Не подключено") }
    var connectedChannel by remember { mutableStateOf<String?>(null) }
    var zelloSession by remember { mutableStateOf<Session?>(null) }

    val channels = remember(context) {
        mutableStateListOf<String>().apply {
            val saved = preferences.getStringSet("channels", null)
            addAll(saved ?: setOf("Умные нелюди"))
        }
    }
    LaunchedEffect(channels.toList()) {
        preferences.edit().putStringSet("channels", LinkedHashSet(channels)).apply()
    }

    DisposableEffect(Unit) {
        onDispose { zelloSession?.disconnect() }
    }

    fun disconnectZello() {
        zelloSession?.disconnect()
        zelloSession = null
        connectedChannel = null
        connectionStatus = "Не подключено"
    }

    fun connectZello(channel: String) {
        val token = SecureTokenStore.read(context)
        if (token.isBlank()) {
            connectionStatus = "Сначала сохрани токен Zello Developer"
            return
        }
        disconnectZello()
        connectionStatus = "Подключаемся к «$channel»…"
        connectedChannel = channel
        val newSession = Session.Builder(context, "wss://zello.io/ws", token, channel)
            .build()
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
            }
            override fun onConnectSucceeded(session: Session) {
                connectionStatus = "Подключено к «$channel» — режим прослушивания"
                connectedChannel = channel
            }
            override fun onDisconnected(session: Session) {
                connectionStatus = "Соединение с Zello закрыто"
                connectedChannel = null
                zelloSession = null
            }
            override fun onSessionWillReconnect(session: Session, reason: ReconnectReason): Boolean {
                connectionStatus = "Связь потеряна. Zello переподключается…"
                return true
            }
            override fun onOutgoingVoiceError(session: Session, stream: OutgoingVoiceStream, error: OutgoingVoiceStreamError) {}
            override fun onOutgoingVoiceStateChanged(session: Session, stream: OutgoingVoiceStream) {}
            override fun onOutgoingVoiceProgress(session: Session, stream: OutgoingVoiceStream, positionMs: Int) {}
            override fun onIncomingVoiceWillStart(session: Session, streamInfo: IncomingVoiceStreamInfo): IncomingVoiceConfiguration? = null
            override fun onIncomingVoiceStarted(session: Session, stream: IncomingVoiceStream) {}
            override fun onIncomingVoiceStopped(session: Session, stream: IncomingVoiceStream) {}
            override fun onIncomingVoiceProgress(session: Session, stream: IncomingVoiceStream, positionMs: Int) {}
            override fun onTextMessage(session: Session, sender: String, message: String) {}
            override fun onImageMessage(session: Session, imageInfo: ImageInfo) {}
            override fun onLocationMessage(session: Session, sender: String, location: Location) {}
        }
        zelloSession = newSession
        if (!newSession.connect()) {
            connectionStatus = "Не удалось запустить подключение. Проверь токен и название канала."
            connectedChannel = null
            zelloSession = null
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
        Scaffold(containerColor = Background, topBar = {
            TopAppBar(title = { Column {
                Text("АЛИСА", fontWeight = FontWeight.Bold)
                Text("Alisa Radio · Zello", fontSize = 12.sp, color = Color.LightGray)
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
                            Text(if (assistantEnabled) "Алиса включена" else "Алиса выключена", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(connectionStatus, color = Color.LightGray, fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { assistantEnabled = !assistantEnabled }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(if (assistantEnabled) "Выключить" else "Включить") }
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
                                }, modifier = Modifier.weight(1f), enabled = tokenInput.isNotBlank()) { Text("Сохранить токен") }
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
                item { Text("Подключение без логина и пароля Zello работает только на прослушивание. Для передачи голоса и музыки потребуется авторизация аккаунта. Алиса и музыкальный поток ещё разрабатываются.", color = Color.LightGray, fontSize = 12.sp) }
            }
        }
    }
}

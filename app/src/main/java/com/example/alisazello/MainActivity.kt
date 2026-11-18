package com.example.alisazello

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF101522)
private val Panel = Color(0xFF1B2434)
private val Accent = Color(0xFF9B8CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlisaApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlisaApp() {
    var assistantEnabled by remember { mutableStateOf(false) }
    var newChannel by remember { mutableStateOf("") }
    val channels = remember { mutableStateListOf("Умные нелюди") }
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
                            Text("Это экран управления. Голосовое подключение пока не настроено.", color = Color.LightGray, fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { assistantEnabled = !assistantEnabled }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(if (assistantEnabled) "Выключить" else "Включить") }
                        }
                    }
                }
                item { Text("Мои каналы", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                items(channels) { channel ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(channel, color = Color.White, fontWeight = FontWeight.Medium)
                                Text("Ещё не подключён к Zello", color = Color.LightGray, fontSize = 12.sp)
                            }
                            TextButton(onClick = { channels.remove(channel) }) { Text("Удалить") }
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
                item { Text("Важно: это первый макет приложения. Кнопки и список каналов работают локально; микрофон, искусственный интеллект, фоновая служба и реальная связь с Zello будут добавлены и проверены отдельно.", color = Color.LightGray, fontSize = 12.sp) }
            }
        }
    }
}

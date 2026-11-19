package com.example.alisazello

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val bg = Color(0xFF101522)
private val panel = Color(0xFF1B2434)
private val accent = Color(0xFF9B8CFF)
data class Persona(val id:Int, val name:String, val nick:String, val style:String, val voice:String)
private val names = listOf("Андрей","Максим","Игорь","Сергей","Денис","Вадим","Павел","Роман","Евгений","Артём","Михаил","Виталий","Олег","Кирилл","Дмитрий","Алексей","Никита","Григорий","Борис","Константин")
private val styles = listOf("Шутник","Ворчун","Автомобилист","Рыбак","Философ","Любитель историй","Спокойный собеседник","Спорщик","Оптимист","Скептик")
private val voices = listOf("Голос 1","Голос 2","Голос 3","Голос 4")
private fun persona(i:Int):Persona { val n=names[i%names.size]; return Persona(i+1,n,"$n ${(i+1).toString().padStart(3,'0')}",styles[i%styles.size],voices[i%voices.size]) }
class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); setContent { BotManagerApp() } } }
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BotManagerApp() {
    var count by remember { mutableIntStateOf(50) }
    var channel by remember { mutableStateOf("Умныенелюди") }\n    val channels = listOf("Умныенелюди", "Перелом", "~Мы Семья~")
    var topic by remember { mutableStateOf("Свободная беседа") }
    var running by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf("Готов к запуску симуляции персонажей.")) }
    val roster = remember(count) { (0 until count.coerceIn(1,1000)).map(::persona) }
    MaterialTheme(colorScheme=darkColorScheme(primary=accent, background=bg, surface=panel)) {
        Scaffold(containerColor=bg, topBar={ TopAppBar(title={ Column { Text("BOT MANAGER",fontWeight=FontWeight.Bold); Text("Персонажи и сценарии",fontSize=12.sp) } }, colors=TopAppBarDefaults.topAppBarColors(containerColor=bg,titleContentColor=Color.White)) }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item { Card(colors=CardDefaults.cardColors(containerColor=panel),shape=RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Сессия",fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Text("Выбрать канал",fontWeight=FontWeight.Medium)\n                    channels.forEach { ch -> OutlinedButton(onClick={channel=ch}, modifier=Modifier.fillMaxWidth()) { Text(if(channel==ch) "✓ $ch" else ch) } }\n                    OutlinedTextField(channel,{channel=it},label={Text("Или ввести канал вручную")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    Text("Количество персонажей: $count",fontWeight=FontWeight.Medium)
                    Slider(value=count.toFloat(),onValueChange={count=it.toInt().coerceIn(1,1000)},valueRange=1f..1000f)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(50,100,500).forEach { n -> OutlinedButton(onClick={count=n}) { Text("$n") } } }
                    OutlinedTextField(topic,{topic=it},label={Text("Тема разговора")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    Button(onClick={ val next=!running; running=next; log=log+if(next) "Симуляция запущена: $count персонажей, канал «$channel», тема «$topic»." else "Сессия остановлена." },modifier=Modifier.fillMaxWidth()) { Text(if(running) "Остановить" else "Запустить группу") }
                    Text(if(running) "Симуляция активна. Это не подключение сотен аккаунтов Zello." else "Персонажи и диалоговая симуляция; подключение массовых аккаунтов не выполняется.",fontSize=12.sp,color=Color.LightGray)
                } } }
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={showList=!showList},modifier=Modifier.weight(1f)) { Text(if(showList) "Скрыть персонажей" else "Список персонажей") }; OutlinedButton(onClick={log=log+ "Персонажи обновлены: $count."}) { Text("Обновить") } } }
                if(showList) { items(roster.take(200),key={it.id}) { p -> Card(colors=CardDefaults.cardColors(containerColor=panel)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(p.nick,fontWeight=FontWeight.Bold); Text("Характер: ${p.style} • ${p.voice}",fontSize=12.sp,color=Color.LightGray) } } }; item { Text("Показаны первые ${minOf(count,200)} из $count. Список ограничен для удобства отображения.") } }
                item { Text("Журнал",fontWeight=FontWeight.Bold,fontSize=18.sp) }
                items(log.takeLast(10).reversed()) { entry -> Card(colors=CardDefaults.cardColors(containerColor=panel)) { Text(entry,Modifier.padding(12.dp),fontSize=13.sp) } }
                item { Text("Автоматические персонажи должны обозначаться прозрачно. Реальное подключение к каналам и аудиопередача требуют совместимой интеграции и разрешений платформы.",fontSize=12.sp,color=Color.LightGray) }
            }
        }
    }
}
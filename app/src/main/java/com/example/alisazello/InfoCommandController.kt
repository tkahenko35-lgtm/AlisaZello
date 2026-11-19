package com.example.alisazello

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Live weather and headline commands. No API key or music service is required. */
internal object InfoCommandController {
    fun isWeatherCommand(text: String): Boolean =
        Regex("(?i)(погод|температур|дожд[ья]|снег|ветер|прогноз)").containsMatchIn(text)

    fun isNewsCommand(text: String): Boolean =
        Regex("(?i)(новост|что в мире|что происходит|последние события|сводка)").containsMatchIn(text)

    fun weather(command: String): String {
        val city = Regex("(?i)(?:в|для|по городу)\\s+([а-яёa-z0-9 .-]{2,50})")
            .find(command)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', '?', '!', ',')
        if (city.isNullOrBlank()) return "Назови город, и я скажу погоду. Например: погода в Москве."
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + enc(city) + "&count=1&language=ru&format=json"
        val geo = JSONObject(String(get(geoUrl), StandardCharsets.UTF_8)).optJSONArray("results")?.optJSONObject(0)
            ?: return "Не нашёл город " + city + ". Назови его чуть точнее."
        val latitude = geo.optDouble("latitude")
        val longitude = geo.optDouble("longitude")
        val name = geo.optString("name", city)
        val country = geo.optString("country", "")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&timezone=auto"
        val current = JSONObject(String(get(url), StandardCharsets.UTF_8)).optJSONObject("current")
            ?: return "Погодный сервис пока не отдал данные."
        val temp = current.optDouble("temperature_2m")
        val feels = current.optDouble("apparent_temperature")
        val humidity = current.optInt("relative_humidity_2m")
        val wind = current.optDouble("wind_speed_10m")
        val rain = current.optDouble("precipitation")
        val code = current.optInt("weather_code")
        return "Погода в " + name + if (country.isNotBlank()) ", " + country else "" +
            ": " + temp.toInt() + " градусов, ощущается как " + feels.toInt() + ". " + weatherText(code) +
            ". Влажность " + humidity + " процентов, ветер " + wind.toInt() + " километров в час, осадки " + rain + " миллиметров."
    }

    fun news(command: String): String {
        val query = Regex("(?i)(?:новости|новост|что происходит|события)\\s+(?:в|про|о)\\s+([а-яёa-z0-9 .-]{2,40})")
            .find(command)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', '?', '!', ',')
        val term = query ?: "главные новости"
        val url = "https://news.google.com/rss/search?q=" + enc(term) + "&hl=ru&gl=RU&ceid=RU:ru"
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(get(url).inputStream(), "UTF-8")
        val headlines = mutableListOf<String>()
        var inItem = false
        var inTitle = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && headlines.size < 5) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) { "item" -> inItem = true; "title" -> if (inItem) inTitle = true }
                XmlPullParser.TEXT -> if (inItem && inTitle) {
                    val title = parser.text.trim()
                    if (title.isNotBlank()) headlines.add(title)
                }
                XmlPullParser.END_TAG -> when (parser.name) { "title" -> inTitle = false; "item" -> inItem = false }
            }
            event = parser.next()
        }
        if (headlines.isEmpty()) return "Свежие заголовки сейчас получить не удалось. Попробуй чуть позже."
        return "Вот свежие заголовки по теме «" + term + "». " +
            headlines.mapIndexed { index, title -> (index + 1).toString() + ". " + title }.joinToString(" ")
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "ясно"
        1, 2 -> "небольшая облачность"
        3 -> "облачно"
        45, 48 -> "туман"
        51, 53, 55 -> "морось"
        61, 63, 65 -> "дождь"
        66, 67 -> "ледяной дождь"
        71, 73, 75, 77 -> "снег"
        80, 81, 82 -> "ливень"
        85, 86 -> "снегопад"
        95, 96, 99 -> "гроза"
        else -> "переменная погода"
    }

    private fun get(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", "VitalikZello/1.0")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) throw IllegalStateException("Сервис ответил HTTP " + status)
            bytes
        } finally { connection.disconnect() }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

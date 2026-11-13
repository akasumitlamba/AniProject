package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Weather from Open-Meteo: free, no API key, no account. The user picks a city once (no
 * location permission); only that city's coordinates are sent. Results are cached 30 minutes.
 */
internal object Weather {
    private fun prefs(context: Context) = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    data class Place(val name: String, val latitude: Double, val longitude: Double)
    data class Now(val temperature: Int, val code: Int, val high: Int, val low: Int, val day: Boolean, val fetchedAt: Long)

    fun place(context: Context): Place? = prefs(context).let { p ->
        p.getString("city", null)?.let { Place(it, p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble()) }
    }
    private fun savePlace(context: Context, place: Place) = prefs(context).edit().putString("city", place.name)
        .putFloat("lat", place.latitude.toFloat()).putFloat("lon", place.longitude.toFloat()).remove("cache").apply()

    fun cached(context: Context): Now? = prefs(context).getString("cache", null)?.let { parse(it, prefs(context).getLong("cached_at", 0)) }

    /** Blocking network call; use a background dispatcher. */
    fun fetch(context: Context, force: Boolean = false): Now? {
        val place = place(context) ?: return null
        val cachedAt = prefs(context).getLong("cached_at", 0)
        if (!force && System.currentTimeMillis() - cachedAt < 30 * 60_000L) cached(context)?.let { return it }
        val body = get("https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1")
        val now = System.currentTimeMillis()
        prefs(context).edit().putString("cache", body).putLong("cached_at", now).apply()
        return parse(body, now)
    }

    private fun parse(body: String, at: Long): Now? = runCatching {
        val json = JSONObject(body); val current = json.getJSONObject("current"); val daily = json.getJSONObject("daily")
        Now(current.getDouble("temperature_2m").roundToInt(), current.getInt("weather_code"),
            daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(), daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            current.optInt("is_day", 1) == 1, at)
    }.getOrNull()

    fun search(query: String): List<Place> {
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&name=" + URLEncoder.encode(query, "UTF-8")))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it) }.map { r ->
            val region = listOfNotNull(r.optString("admin1").takeIf { it.isNotBlank() }, r.optString("country").takeIf { it.isNotBlank() }).joinToString(", ")
            Place(r.getString("name") + if (region.isNotEmpty()) ", $region" else "", r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    private fun get(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000; connection.setRequestProperty("User-Agent", "AniHome")
        try { require(connection.responseCode == 200); return connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }

    /** WMO weather codes → short description and a glyph. */
    fun describe(code: Int, day: Boolean): Pair<String, String> = when (code) {
        0 -> (if (day) "Clear" else "Clear night") to (if (day) "☀" else "☾")
        1, 2 -> "Partly cloudy" to (if (day) "⛅" else "☁")
        3 -> "Overcast" to "☁"
        45, 48 -> "Fog" to "≋"
        in 51..57 -> "Drizzle" to "☂"
        in 61..67, in 80..82 -> "Rain" to "☂"
        in 71..77, 85, 86 -> "Snow" to "❄"
        in 95..99 -> "Thunderstorm" to "ϟ"
        else -> "—" to "☁"
    }

    /** Ask for a city (geocoded by Open-Meteo), then [done]. */
    fun chooseCity(context: Context, scope: CoroutineScope, done: () -> Unit) {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val input = EditText(context).apply { hint = "City name"; isSingleLine = true; setText(place(context)?.name?.substringBefore(",").orEmpty()) }
        val box = LinearLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        MaterialAlertDialogBuilder(context).setTitle("Weather location")
            .setMessage("Weather comes from Open-Meteo (free, no account). Only this city's coordinates are sent.")
            .setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim(); if (query.isEmpty()) return@setPositiveButton
                scope.launch {
                    val places = runCatching { withContext(Dispatchers.IO) { search(query) } }.getOrDefault(emptyList())
                    if (places.isEmpty()) { Toast.makeText(context, "No place called \"$query\" found", Toast.LENGTH_SHORT).show(); return@launch }
                    MaterialAlertDialogBuilder(context).setTitle("Choose location")
                        .setItems(places.map { it.name }.toTypedArray()) { _, which -> savePlace(context, places[which]); done() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }
}

/** Home widget: temperature, condition, high/low and place. Tap to refresh; long-press options as usual. */
internal class WeatherCard(context: Context) : LinearLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val glyph = text(30f)
    private val temperature = text(40f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val condition = text(13f)
    private val place = text(12f, 0xFFB9BDC6.toInt())
    private val range = text(12f, 0xFFB9BDC6.toInt())
    private var refreshJob: Job? = null

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xE6243B6B.toInt(), 0xE6152036.toInt())).apply { cornerRadius = dp(20).toFloat() }
        clipToOutline = true
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(temperature); top.addView(glyph, LayoutParams(-2, -2).apply { marginStart = dp(10) })
        addView(top); addView(condition); addView(range)
        addView(place, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        isClickable = true; isFocusable = true
        setOnClickListener { if (Weather.place(context) == null) Weather.chooseCity(context, scope) { refresh(true) } else refresh(true) }
        render(Weather.cached(context))
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { refresh(false) }
            override fun onViewDetachedFromWindow(v: View) { refreshJob?.cancel() }
        })
    }
    fun changeCity() = Weather.chooseCity(context, scope) { refresh(true) }

    private fun text(size: Float, color: Int = Color.WHITE) = TextView(context).apply { textSize = size; setTextColor(color); includeFontPadding = false; maxLines = 1 }

    private fun refresh(force: Boolean) {
        if (Weather.place(context) == null) { render(null); return }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val now = runCatching { withContext(Dispatchers.IO) { Weather.fetch(context, force) } }.getOrNull()
            if (now != null) render(now) else if (force) Toast.makeText(context, "Weather unavailable right now", Toast.LENGTH_SHORT).show()
            delay(30 * 60_000L); if (isAttachedToWindow) refresh(false)
        }
    }

    private fun render(now: Weather.Now?) {
        val where = Weather.place(context)
        if (where == null) {
            temperature.text = "—"; glyph.text = "☀"; condition.text = "Tap to set your city"; range.text = ""; place.text = "Weather"
            contentDescription = "Weather. Tap to choose a city"; return
        }
        place.text = where.name.substringBefore(",")
        if (now == null) { temperature.text = "—"; condition.text = "Loading…"; range.text = ""; return }
        val (label, symbol) = Weather.describe(now.code, now.day)
        temperature.text = "${now.temperature}°"; glyph.text = symbol; condition.text = label; range.text = "H ${now.high}°  L ${now.low}°"
        contentDescription = "Weather in ${place.text}: ${now.temperature} degrees, $label, high ${now.high}, low ${now.low}"
    }
}

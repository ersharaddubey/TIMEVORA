package com.timevora.app.data

import android.content.Context
import com.timevora.app.audio.AmbientSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** One timevora feature as shown on Home, in the More hub and on its intro page. Content comes from the server (admin panel) or the bundled defaults. */
data class FeatureInfo(
    val id: String, val route: String, val emoji: String, val title: String, val tagline: String,
    val why: String, val whyHi: String, val useWhen: String, val goal: String,
    val points: List<String>, val steps: List<String>, val promise: String,
    val color: Long, val order: Int, val live: Boolean
)

data class BannerInfo(val id: String, val emoji: String, val title: String, val subtitle: String, val color: Long, val target: String)

data class Catalog(
    val features: List<FeatureInfo>, val banners: List<BannerInfo>, val announcement: String, val tips: List<String>,
    /** Extra ambient sounds uploaded in the admin panel (MP3 on Cloudinary). Empty when none / offline. */
    val sounds: List<AmbientSound> = emptyList()
) {
    /** Banners for the Home carousel. */
    fun homeBanners(): List<BannerInfo> = banners
}

object CatalogStore {
    private fun hex(s: String, fallback: Long): Long =
        runCatching { 0xFF000000L or s.removePrefix("#").toLong(16) }.getOrDefault(fallback)

    private fun JSONObject.strList(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    fun parse(json: String): Catalog {
        val o = JSONObject(json)
        val fa = o.getJSONArray("features")
        val features = (0 until fa.length()).map { fa.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }.map { f ->
            FeatureInfo(
                id = f.getString("id"), route = f.optString("route", "Info"), emoji = f.optString("emoji", "✨"),
                title = f.getString("title"), tagline = f.optString("tagline"), why = f.optString("why"), whyHi = f.optString("whyHi"),
                useWhen = f.optString("useWhen"), goal = f.optString("goal", "productive"), points = f.strList("points"), steps = f.strList("steps"),
                promise = f.optString("promise"), color = hex(f.optString("color"), 0xFFFFE7ED), order = f.optInt("order", 0), live = f.optBoolean("live", false)
            )
        }.sortedBy { it.order }
        val ba = o.optJSONArray("banners")
        val banners = if (ba == null) emptyList() else (0 until ba.length()).map { ba.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }.map { b ->
            BannerInfo(b.optString("id"), b.optString("emoji", "✦"), b.optString("title"), b.optString("subtitle"), hex(b.optString("color"), 0xFFFF3F6C), b.optString("target"))
        }
        val an = o.opt("announcement")
        val announcement = when (an) {
            is JSONObject -> if (an.optBoolean("enabled")) an.optString("text") else ""
            is String -> an
            else -> ""
        }
        return Catalog(features, banners, announcement, o.strList("tips"), parseSounds(o))
    }

    /** Admin-uploaded ambient sounds. Only https links with a safe id are accepted; built-in ids can never be replaced. */
    private fun parseSounds(o: JSONObject): List<AmbientSound> {
        val a = o.optJSONArray("sounds") ?: return emptyList()
        val idRe = Regex("^snd-[a-z0-9]{4,24}$")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { s ->
            val id = s.optString("id")
            val url = s.optString("url")
            if (!idRe.matches(id) || !url.startsWith("https://")) return@mapNotNull null
            AmbientSound(id, s.optString("label").ifBlank { "Sound" }, s.optString("emoji").ifBlank { "🎵" }, url)
        }
    }

    fun defaults(c: Context): Catalog =
        parse(c.assets.open("defaults.json").bufferedReader().use { it.readText() })

    /** Last catalog fetched from the server, or the bundled defaults when offline / not set up. */
    fun current(c: Context): Catalog {
        val cached = c.getSharedPreferences("timevora", Context.MODE_PRIVATE).getString("catalog_json", null)
        if (cached != null) runCatching { parse(cached) }.getOrNull()?.takeIf { it.features.isNotEmpty() }?.let { return it }
        return defaults(c)
    }
}

/** Small HTTP client for the optional timevora server (feature catalog, live rooms, anonymous feature-open counts). No third-party libraries. */
object Remote {
    /**
     * Your server address, set ONCE here before building (for example "https://timevora.onrender.com",
     * or "http://192.168.1.5:3000" for testing on your Wi-Fi with a debug build).
     * Users then never type anything: they just press the Connect button in the app.
     * Leave it empty to let users type the address themselves.
     */
    const val DEFAULT_SERVER = "https://timevora-server.vercel.app"

    val hasDefault: Boolean get() = DEFAULT_SERVER.isNotBlank()

    private fun prefs(c: Context) = c.getSharedPreferences("timevora", Context.MODE_PRIVATE)

    /** The server the user connected to. Empty until the user presses Connect (or saves an address). */
    fun baseUrl(c: Context): String = prefs(c).getString("server_url", null)?.takeIf { it.isNotBlank() } ?: ""

    fun isConfigured(c: Context) = baseUrl(c).isNotEmpty()

    fun normalize(raw: String): String {
        val s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        val local = Regex("^(localhost|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|\\d+\\.\\d+\\.\\d+\\.\\d+).*").matches(s)
        return (if (local) "http://" else "https://") + s
    }

    fun setBaseUrl(c: Context, raw: String) {
        prefs(c).edit().putString("server_url", normalize(raw)).remove("catalog_json").putLong("catalog_at", 0L).apply()
    }

    fun deviceId(c: Context): String {
        val p = prefs(c)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
    }

    fun displayName(c: Context): String = prefs(c).getString("display_name", "") ?: ""
    fun setDisplayName(c: Context, name: String) { prefs(c).edit().putString("display_name", name.trim().take(24)).apply() }

    fun shareCounts(c: Context): Boolean = prefs(c).getBoolean("share_counts", true)
    fun setShareCounts(c: Context, on: Boolean) { prefs(c).edit().putBoolean("share_counts", on).apply() }

    suspend fun call(c: Context, method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): JSONObject = withContext(Dispatchers.IO) {
        val base = baseUrl(c)
        if (base.isEmpty()) throw java.io.IOException("Server address is not set")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 6000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            for ((hk, hv) in headers) conn.setRequestProperty(hk, hv)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299) throw java.io.IOException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Server error ($code)")
            json ?: JSONObject()
        } finally {
            conn.disconnect()
        }
    }

    /** Fetches the latest feature catalog. Returns null when not configured, throttled (60 s) or offline, so callers simply keep what they have. */
    suspend fun refreshCatalog(c: Context, force: Boolean = false): Catalog? {
        val base = baseUrl(c)
        if (base.isEmpty()) return null
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("catalog_at", 0L) < 60_000L && p.getString("catalog_url", "") == base) return null
        return runCatching {
            val o = call(c, "GET", "/api/catalog")
            val cat = CatalogStore.parse(o.toString())
            if (cat.features.isEmpty()) return null
            p.edit().putString("catalog_json", o.toString()).putLong("catalog_at", now).putString("catalog_url", base).apply()
            cat
        }.getOrNull()
    }

    /** Anonymous counter: only the feature id is sent. Respects the Settings switch. */
    suspend fun trackOpen(c: Context, featureId: String) {
        if (!isConfigured(c) || !shareCounts(c)) return
        runCatching { call(c, "POST", "/api/event", JSONObject().put("feature", featureId)) }
    }
}

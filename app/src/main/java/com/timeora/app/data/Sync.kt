package com.timevora.app.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opt-in backup of the user's own data (all database tables + settings) to the timevora server, which stores it in MongoDB.
 * OFF by default. Needs the server connection (Remote) and the user's explicit consent (Settings > Online features > Back up my data).
 * Each sync uploads a full snapshot in chunks; the server removes rows that were deleted on the phone.
 */
object Sync {
    /** Must match SYNC_TABLES in the server. */
    private val TABLES = listOf(
        "usage_sessions", "app_limits", "planner_items", "planner_done", "app_categories", "category_limits", "focus_sessions",
        "habits", "habit_logs", "xp_transactions", "daily_scores", "achievement_unlocks", "exam_subjects", "exam_events", "study_logs"
    )
    /** Settings files that are backed up (file name -> short tag used in the key). Live/transient files are left out. */
    private val PREF_FILES = mapOf("timevora" to "main", "timevora_challenges" to "chal", "timevora_focus" to "focus", "timevora_notes" to "notes")
    /** Never uploaded: connection details, secrets, caches. */
    private val SKIP_KEYS = setOf("server_url", "device_id", "sync_key", "sync_enabled", "sync_last", "sync_error", "catalog_json", "catalog_at", "catalog_url")
    private const val CHUNK = 500
    private const val MIN_GAP_MS = 15 * 60_000L
    private const val TIMEOUT_MS = 30_000
    private val lock = Mutex()

    private fun p(c: Context) = c.getSharedPreferences("timevora", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = p(c).getBoolean("sync_enabled", false)
    fun lastSync(c: Context): Long = p(c).getLong("sync_last", 0L)
    fun lastError(c: Context): String = p(c).getString("sync_error", "") ?: ""

    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("sync_enabled", on).apply()
        if (on) schedule(c) else cancel(c)
    }

    fun schedule(c: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("timevora_sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(c: Context) { WorkManager.getInstance(c).cancelUniqueWork("timevora_sync") }

    /** Uploads everything. Returns true when finished; false when skipped (off / not connected / too soon / already running) or failed (see lastError). */
    suspend fun run(c: Context, force: Boolean = false): Boolean {
        if (!enabled(c) || !Remote.isConfigured(c)) return false
        if (!force && System.currentTimeMillis() - lastSync(c) < MIN_GAP_MS) return false
        if (!lock.tryLock()) return false
        try {
            doSync(c)
            p(c).edit().putLong("sync_last", System.currentTimeMillis()).remove("sync_error").apply()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            p(c).edit().putString("sync_error", e.message ?: "unknown error").apply()
            return false
        } finally {
            lock.unlock()
        }
    }

    /** Removes everything this phone has stored on the server and forgets the device key. */
    suspend fun deleteRemote(c: Context) {
        if (p(c).getString("sync_key", null).isNullOrEmpty()) return
        Remote.call(c, "DELETE", "/api/sync/data", null, headers(c))
        p(c).edit().remove("sync_key").remove("sync_last").remove("sync_error").apply()
    }

    private suspend fun headers(c: Context): Map<String, String> {
        val id = Remote.deviceId(c)
        val saved = p(c).getString("sync_key", null)
        val key: String = if (!saved.isNullOrEmpty()) saved else {
            val r = Remote.call(c, "POST", "/api/sync/register", JSONObject().put("deviceId", id))
            r.getString("deviceKey").also { p(c).edit().putString("sync_key", it).apply() }
        }
        return mapOf("x-device-id" to id, "x-device-key" to key)
    }

    private suspend fun doSync(c: Context) {
        val syncId = UUID.randomUUID().toString()
        try {
            upload(c, headers(c), syncId)
        } catch (e: IOException) {
            // The server lost this device (for example its data was wiped): register again once and retry.
            if (e.message?.contains("not registered") == true) {
                p(c).edit().remove("sync_key").apply()
                upload(c, headers(c), syncId)
            } else throw e
        }
    }

    private suspend fun upload(c: Context, h: Map<String, String>, syncId: String) {
        val db = timevoraDatabase.get(c).openHelper.readableDatabase
        for (t in TABLES) pushTable(c, h, db, t, syncId)
        Remote.call(c, "POST", "/api/sync/prefs", JSONObject().put("prefs", collectPrefs(c)), h, TIMEOUT_MS)
        Remote.call(c, "POST", "/api/sync/finish", JSONObject().put("syncId", syncId).put("tables", JSONArray(TABLES)), h, TIMEOUT_MS)
    }

    private fun pkColumns(db: SupportSQLiteDatabase, table: String): List<String> {
        val cols = ArrayList<Pair<Int, String>>()
        db.query("PRAGMA table_info($table)").use { cur ->
            val iName = cur.getColumnIndexOrThrow("name")
            val iPk = cur.getColumnIndexOrThrow("pk")
            while (cur.moveToNext()) { val pk = cur.getInt(iPk); if (pk > 0) cols.add(pk to cur.getString(iName)) }
        }
        return cols.sortedBy { it.first }.map { it.second }
    }

    private suspend fun pushTable(c: Context, h: Map<String, String>, db: SupportSQLiteDatabase, table: String, syncId: String) = withContext(Dispatchers.IO) {
        val pks = pkColumns(db, table)
        var batch = JSONArray()
        suspend fun flush() {
            if (batch.length() == 0) return
            Remote.call(c, "POST", "/api/sync/rows", JSONObject().put("table", table).put("syncId", syncId).put("rows", batch), h, TIMEOUT_MS)
            batch = JSONArray()
        }
        db.query("SELECT * FROM $table").use { cur ->
            val names = cur.columnNames
            while (cur.moveToNext()) {
                val d = JSONObject()
                val pkVals = HashMap<String, String>()
                for (i in names.indices) {
                    val v: Any? = when (cur.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cur.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> cur.getString(i)
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        else -> null // blobs are not backed up
                    }
                    if (v != null) d.put(names[i], v)
                    if (names[i] in pks) pkVals[names[i]] = cur.getString(i) ?: ""
                }
                batch.put(JSONObject().put("k", pks.joinToString(":") { pkVals[it] ?: "" }).put("d", d))
                if (batch.length() >= CHUNK) flush()
            }
        }
        flush()
    }

    private fun collectPrefs(c: Context): JSONObject {
        val out = JSONObject()
        val bad = Regex("[^A-Za-z0-9_]")
        for ((file, tag) in PREF_FILES) {
            for ((k, v) in c.getSharedPreferences(file, Context.MODE_PRIVATE).all) {
                if (file == "timevora" && k in SKIP_KEYS) continue
                val key = (tag + "__" + k.replace(bad, "_")).take(60)
                when (v) {
                    is Boolean -> out.put(key, v)
                    is Int -> out.put(key, v)
                    is Long -> out.put(key, v)
                    is Float -> out.put(key, v.toDouble())
                    is String -> out.put(key, v)
                    is Set<*> -> out.put(key, JSONArray(v.filterIsInstance<String>()))
                    else -> {}
                }
            }
        }
        return out
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.run(applicationContext, force = true)
        return Result.success()
    }
}

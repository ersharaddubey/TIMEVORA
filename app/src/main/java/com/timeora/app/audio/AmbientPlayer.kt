package com.timevora.app.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.timevora.app.MainActivity
import com.timevora.app.notifications.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ambient sounds for Focus mode: the one entry point the UI and FocusManager use.
 * The audio itself runs in [AmbientService] (a media-playback foreground service) so it keeps going with the screen off.
 */
object AmbientPlayer {
    private const val PREFS = "timevora_ambient"

    /** The sound that is playing right now, or null. Compose reads this directly, so the UI updates on its own. */
    var current by mutableStateOf<AmbientSound?>(null)
        internal set

    /** When the sleep timer stops the sound (epoch millis), or 0 when no timer is running. */
    var sleepEndMillis by mutableLongStateOf(0L)
        internal set

    /** 0..1, shared by the UI slider and the service. */
    var volume by mutableFloatStateOf(0.6f)
        internal set

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The sound the person picked last (it starts with the next focus session). Null means "no sound". */
    fun savedSound(c: Context): AmbientSound? {
        val p = prefs(c)
        val id = p.getString("sound", null) ?: return null
        AmbientSound.fromId(id)?.let { return it }
        // An uploaded (cloud) sound: its details are saved with it, so it still works before the catalog is loaded.
        val url = p.getString("sound_url", null)?.takeIf { it.startsWith("https://") } ?: return null
        return AmbientSound(id, p.getString("sound_label", null) ?: "Sound", p.getString("sound_emoji", null) ?: "🎵", url)
    }

    fun saveSound(c: Context, s: AmbientSound?) {
        prefs(c).edit().putString("sound", s?.id).putString("sound_url", s?.url).putString("sound_label", s?.label).putString("sound_emoji", s?.emoji).apply()
    }

    /** Sleep timer length in minutes; 0 = off (the sound ends with the focus session). */
    fun savedSleepMinutes(c: Context): Int = prefs(c).getInt("sleep_min", 0)

    fun loadVolume(c: Context) { volume = prefs(c).getFloat("volume", 0.6f).coerceIn(0f, 1f) }

    fun setVolume(c: Context, v: Float) {
        volume = v.coerceIn(0f, 1f)
        prefs(c).edit().putFloat("volume", volume).apply()
    }

    /** Starts (or switches to) [sound]. Call this while the app is on screen. */
    fun play(c: Context, sound: AmbientSound) {
        val app = c.applicationContext
        saveSound(app, sound)
        loadVolume(app)
        // Switching sounds keeps a countdown that is already running; a fresh start begins one.
        if (current == null) {
            val mins = savedSleepMinutes(app)
            sleepEndMillis = if (mins > 0) System.currentTimeMillis() + mins * 60_000L else 0L
        }
        ContextCompat.startForegroundService(
            app,
            Intent(app, AmbientService::class.java)
                .putExtra(AmbientService.EXTRA_SOUND, sound.id)
                .putExtra(AmbientService.EXTRA_URL, sound.url)
                .putExtra(AmbientService.EXTRA_LABEL, sound.label)
                .putExtra(AmbientService.EXTRA_EMOJI, sound.emoji)
        )
    }

    /** Sets the sleep timer. While a sound plays the countdown starts now; otherwise it is used the next time a sound starts. */
    fun setSleepMinutes(c: Context, minutes: Int) {
        prefs(c).edit().putInt("sleep_min", minutes).apply()
        if (current != null) sleepEndMillis = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
    }

    /** Fades the sound out and stops it (use from the UI). */
    fun stop(c: Context) {
        if (current == null) return
        val app = c.applicationContext
        app.startService(Intent(app, AmbientService::class.java).setAction(AmbientService.ACTION_STOP))
    }

    /** Stops right away. Used when a focus session ends, possibly while the app is in the background. */
    fun stopNow(c: Context) {
        val app = c.applicationContext
        app.stopService(Intent(app, AmbientService::class.java))
        current = null
        sleepEndMillis = 0L
    }
}

class AmbientService : Service() {
    companion object {
        const val EXTRA_SOUND = "sound"
        const val EXTRA_URL = "sound_url"
        const val EXTRA_LABEL = "sound_label"
        const val EXTRA_EMOJI = "sound_emoji"
        const val ACTION_STOP = "com.timevora.app.audio.STOP"
        private const val NOTIF_ID = 77
        private const val FADE_IN_MS = 1500L
        private const val FADE_OUT_MS = 900L
        private const val SLEEP_FADE_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var out: Out? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var duck = 1f
    @Volatile private var pausedByFocus = false
    @Volatile private var stopAt = 0L
    private var noisyRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        // Headphones unplugged: do not suddenly blast rain out of the speaker.
        override fun onReceive(context: Context, intent: Intent) { requestFadeOut() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (job?.isActive == true) requestFadeOut() else stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_SOUND)
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val sound = AmbientSound.fromId(id)
            ?: if (!id.isNullOrBlank() && url.startsWith("https://")) {
                AmbientSound(id, intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Sound" }, intent?.getStringExtra(EXTRA_EMOJI).orEmpty().ifBlank { "🎵" }, url)
            } else null
        if (sound == null) { stopSelf(); return START_NOT_STICKY }
        goForeground(sound)
        begin(sound)
        return START_NOT_STICKY
    }

    private fun goForeground(sound: AmbientSound) {
        Channels.ensure(this)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 5, Intent(this, AmbientService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, Channels.AMBIENT)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("${sound.emoji} ${sound.label} playing")
            .setContentText("Ambient sound for your focus session")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    private fun requestFadeOut() {
        if (stopAt == 0L) stopAt = System.currentTimeMillis()
    }

    private fun begin(sound: AmbientSound) {
        job?.cancel()
        releaseOut()
        stopAt = 0L
        pausedByFocus = false
        duck = 1f
        job = scope.launch {
            val o = try {
                openOut(sound)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't load ${sound.label}. Check your internet and try again.")
                AmbientPlayer.current = null
                AmbientPlayer.sleepEndMillis = 0L
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            if (!isActive) { o.release(); return@launch } // another sound was picked while this one was loading
            out = o
            requestFocus()
            registerNoisy()
            o.volume(0f)
            o.start()
            AmbientPlayer.current = sound
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val end = AmbientPlayer.sleepEndMillis
                var factor = minOf(1f, (now - startedAt) / FADE_IN_MS.toFloat())
                if (end > 0L) {
                    if (now >= end) break
                    factor = minOf(factor, (end - now) / SLEEP_FADE_MS.toFloat())
                }
                if (stopAt > 0L) {
                    val out = 1f - (now - stopAt) / FADE_OUT_MS.toFloat()
                    if (out <= 0f) break
                    factor = minOf(factor, out)
                }
                if (!pausedByFocus) {
                    val v = (AmbientPlayer.volume * duck * factor).coerceIn(0f, 1f)
                    // Squaring gives finer control at the quiet end, which is where most people listen.
                    try { o.volume(v * v) } catch (_: Exception) {}
                }
                delay(100)
            }
            AmbientPlayer.current = null
            AmbientPlayer.sleepEndMillis = 0L
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** One playing sound, whether it is generated (AudioTrack) or an uploaded MP3 (MediaPlayer). */
    private interface Out {
        fun start()
        fun pause()
        fun volume(v: Float)
        fun release()
    }

    private class PcmOut(private val t: AudioTrack) : Out {
        override fun start() = t.play()
        override fun pause() = t.pause()
        override fun volume(v: Float) { t.setVolume(v) }
        override fun release() {
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
    }

    private class FileOut(private val m: MediaPlayer) : Out {
        override fun start() = m.start()
        override fun pause() = m.pause()
        override fun volume(v: Float) = m.setVolume(v, v)
        override fun release() {
            try { m.stop() } catch (_: Exception) {}
            try { m.release() } catch (_: Exception) {}
        }
    }

    private suspend fun openOut(sound: AmbientSound): Out {
        if (!sound.isCloud) return PcmOut(buildTrack(AmbientSynth.get(sound)))
        var mp: MediaPlayer? = null
        try {
            withContext(Dispatchers.IO) {
                val file = AmbientCloud.file(this@AmbientService, sound) // downloads the first time, then uses the saved copy
                val m = MediaPlayer()
                mp = m
                m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                m.setDataSource(file.absolutePath)
                m.isLooping = true
                m.prepare()
            }
            return FileOut(mp ?: throw IllegalStateException("Player was not created"))
        } catch (e: Throwable) {
            try { mp?.release() } catch (_: Exception) {}
            throw e
        }
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AmbientSynth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1) // loop forever, gap-free
        return t
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> requestFadeOut()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { pausedByFocus = true; try { out?.pause() } catch (_: Exception) {} }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duck = 0.3f }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        duck = 1f
                        if (pausedByFocus) { pausedByFocus = false; try { out?.start() } catch (_: Exception) {} }
                    }
                    else -> {}
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun releaseOut() {
        out?.release()
        out = null
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseOut()
        if (noisyRegistered) { try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}; noisyRegistered = false }
        focusRequest?.let { try { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        AmbientPlayer.current = null
        AmbientPlayer.sleepEndMillis = 0L
        super.onDestroy()
    }
}

package com.ssgenterprises.radioreplay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records continuously and plays replays.
 * Runs with the screen off and while other apps are open.
 */
class ReplayService : Service() {

    companion object {
        private const val TAG = "RadioReplay"
        const val ACTION_START = "com.ssgenterprises.radioreplay.START"
        const val ACTION_REPLAY = "com.ssgenterprises.radioreplay.REPLAY"
        const val ACTION_STOP_REPLAY = "com.ssgenterprises.radioreplay.STOP_REPLAY"
        const val ACTION_QUIT = "com.ssgenterprises.radioreplay.QUIT"
        const val EXTRA_SECONDS = "seconds"
        const val MAX_SECONDS = 240
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** The running service, or null. The activity polls this (same process). */
        @Volatile var instance: ReplayService? = null
            private set
    }

    var engine: ReplayEngine? = null
        private set
    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) start()
            ACTION_REPLAY -> engine?.replay(intent.getIntExtra(EXTRA_SECONDS, 10))
            ACTION_STOP_REPLAY -> engine?.stop()
            ACTION_QUIT -> { shutdown(); return START_NOT_STICKY }
        }
        // A notification action arriving after the service died: don't start recording from the
        // background (Android forbids starting the microphone there), just go away.
        if (!running) stopSelf()
        return START_NOT_STICKY
    }

    fun replay(seconds: Int) = engine?.replay(seconds)
    fun stopReplay() = engine?.stop()

    // ---------------------------------------------------------------- recording

    @SuppressLint("MissingPermission") // checked by MainActivity before starting
    private fun start() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val record = openRecorder()
        if (record == null) {
            toast("Could not open audio input")
            shutdown()
            return
        }
        val rate = record.sampleRate
        val e = ReplayEngine(rate, MAX_SECONDS)
        e.pauseRecordingDuringReplay = Prefs.pauseDuringReplay(this)
        engine = e
        running = true
        instance = this

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioReplay:recording")
            .apply { acquire() }

        recordThread = Thread({ recordLoop(record, e) }, "replay-record").apply { start() }
        playThread = Thread({ playLoop(e) }, "replay-play").apply { start() }
        Log.i(TAG, "recording at $rate Hz")
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        // VOICE_RECOGNITION turns off most AGC/noise suppression, which mangles radio audio.
        // When a wired headset or USB audio adapter is plugged in, Android records from it instead.
        for (rate in intArrayOf(16000, 48000, 44100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            try {
                val r = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, rate / 2 * 2)            // >= 0.5 s of buffering
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (ex: Exception) {
                Log.w(TAG, "AudioRecord $rate Hz failed", ex)
            }
        }
        return null
    }

    private fun recordLoop(record: AudioRecord, e: ReplayEngine) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(e.sampleRate / 50)  // 20 ms blocks
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) e.capture(buf, n)
                else if (n < 0) { Log.e(TAG, "AudioRecord.read error $n"); Thread.sleep(50) }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "record loop", ex)
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    // ---------------------------------------------------------------- playback

    private fun playLoop(e: ReplayEngine) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = e.sampleRate
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))   // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val buf = ShortArray(rate / 50)  // 20 ms
        var gen = e.generation
        var active = false
        try {
            while (running) {
                val k = e.readPlayback(buf, buf.size, gen)
                when {
                    k < 0 -> {                // new replay or stop: drop queued audio now
                        gen = e.generation
                        track.pause(); track.flush()
                        active = false
                    }
                    k > 0 -> {
                        if (!active) { track.play(); active = true }
                        track.write(buf, 0, k)
                    }
                    else -> {
                        if (active) { track.stop(); active = false }  // stop() lets the tail play out
                        Thread.sleep(10)
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "play loop", ex)
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    // ---------------------------------------------------------------- saving

    /** Save the last [seconds] seconds as a WAV in Music/RadioReplay. */
    fun saveWav(seconds: Int = MAX_SECONDS) {
        val e = engine ?: return
        Thread {
            val data = e.snapshot(seconds)
            val name = "replay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
            try {
                val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/RadioReplay")
                    }
                    val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("MediaStore insert failed")
                    contentResolver.openOutputStream(uri)!!.use { WavWriter.write(it, data, e.sampleRate) }
                    "Music/RadioReplay/$name"
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "RadioReplay").apply { mkdirs() }
                    val f = File(dir, name)
                    FileOutputStream(f).use { WavWriter.write(it, data, e.sampleRate) }
                    f.absolutePath
                }
                toast("Saved ${data.size / e.sampleRate} s to $where")
            } catch (ex: Exception) {
                Log.e(TAG, "save", ex)
                toast("Save failed: ${ex.message}")
            }
        }.start()
    }

    // ---------------------------------------------------------------- lifecycle

    fun shutdown() {
        running = false
        recordThread?.join(1000); playThread?.join(1000)
        recordThread = null; playThread = null
        engine = null
        instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun servicePending(action: String, code: Int, seconds: Int = 0): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, ReplayService::class.java).setAction(action).putExtra(EXTRA_SECONDS, seconds),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_notification)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Radio Replay is recording")
            .setContentText("Last 240 s kept. Tap to open.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Last 10 s", servicePending(ACTION_REPLAY, 1, 10)).build())
            .addAction(Notification.Action.Builder(icon, "Last 30 s", servicePending(ACTION_REPLAY, 2, 30)).build())
            .addAction(Notification.Action.Builder(icon, "Stop", servicePending(ACTION_STOP_REPLAY, 3)).build())
            .build()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}

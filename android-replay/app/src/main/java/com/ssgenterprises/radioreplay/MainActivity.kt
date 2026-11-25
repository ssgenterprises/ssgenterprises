package com.ssgenterprises.radioreplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun pauseDuringReplay(c: Context) = p(c).getBoolean("pauseDuringReplay", false)
    fun setPauseDuringReplay(c: Context, v: Boolean) = p(c).edit().putBoolean("pauseDuringReplay", v).apply()
    fun keepScreenOn(c: Context) = p(c).getBoolean("keepScreenOn", false)
    fun setKeepScreenOn(c: Context, v: Boolean) = p(c).edit().putBoolean("keepScreenOn", v).apply()
    fun autoStart(c: Context) = p(c).getBoolean("autoStart", true)
    fun setAutoStart(c: Context, v: Boolean) = p(c).edit().putBoolean("autoStart", v).apply()
}

class MainActivity : Activity() {

    private val durations = intArrayOf(5, 10, 20, 30, 60, 120, 240)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var playStatus: TextView
    private lateinit var level: ProgressBar
    private lateinit var startButton: Button
    private val replayButtons = ArrayList<Button>()
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button

    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 100) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        applyKeepScreenOn()
        requestNeededPermissions()
        if (Prefs.autoStart(this) && hasMic() && ReplayService.instance == null) startRecording()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---------------------------------------------------------------- actions

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val want = mutableListOf<String>()
        if (!hasMic()) want += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) want += Manifest.permission.POST_NOTIFICATIONS
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMic() && Prefs.autoStart(this) && ReplayService.instance == null) startRecording()
    }

    private fun startRecording() {
        if (!hasMic()) {
            toast("Microphone permission is needed to record")
            requestNeededPermissions()
            return
        }
        startForegroundService(Intent(this, ReplayService::class.java).setAction(ReplayService.ACTION_START))
    }

    private fun quit() {
        ReplayService.instance?.shutdown()
    }

    private fun replay(seconds: Int) {
        val s = ReplayService.instance ?: return toast("Start recording first")
        s.replay(seconds)
    }

    private fun applyKeepScreenOn() {
        if (Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- UI

    private fun refresh() {
        val e = ReplayService.instance?.engine
        val on = e != null
        startButton.text = if (on) "Stop recording" else "Start recording"
        replayButtons.forEach { it.isEnabled = on }
        stopButton.isEnabled = e?.playing == true
        saveButton.isEnabled = on
        if (e == null) {
            status.text = "Not recording"
            playStatus.text = ""
            level.progress = 0
            return
        }
        status.text = "● Recording  ${e.sampleRate / 1000} kHz   buffered %.0f s of ${e.maxSeconds} s"
            .format(e.bufferedSeconds)
        level.progress = (e.inputLevel * 100).toInt()
        playStatus.text = if (e.playing)
            "▶ Replaying last ${label(e.requestedSeconds)}  —  %.0f s left".format(e.remainingSeconds)
        else "Live"
    }

    private fun label(s: Int) = if (s >= 60) "${s / 60} min" else "$s s"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bigButton(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(color)
        minHeight = dp(88)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Radio Replay"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(8), 0, dp(4)) }
        col.addView(status)
        col.addView(TextView(this).apply { text = "Input level"; textSize = 12f })
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(level)
        playStatus = TextView(this).apply {
            textSize = 20f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(playStatus)

        val blue = Color.rgb(0x1E, 0x5A, 0xA8)
        durations.forEach { s -> replayButtons += bigButton(label(s), blue) { replay(s) } }
        col.addView(row(*replayButtons.subList(0, 4).toTypedArray()))
        col.addView(row(*replayButtons.subList(4, 7).toTypedArray()))
        stopButton = bigButton("STOP", Color.rgb(0xC6, 0x28, 0x28)) { ReplayService.instance?.stopReplay() }
        saveButton = bigButton("SAVE", Color.rgb(0x2E, 0x7D, 0x32)) {
            ReplayService.instance?.saveWav() ?: toast("Start recording first")
        }
        col.addView(row(stopButton, saveButton))

        startButton = Button(this).apply {
            setOnClickListener { if (ReplayService.instance != null) quit() else startRecording() }
        }
        col.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        col.addView(CheckBox(this).apply {
            text = "Don't record while replaying (use if the phone's mic can hear its own speaker)"
            isChecked = Prefs.pauseDuringReplay(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                Prefs.setPauseDuringReplay(this@MainActivity, v)
                ReplayService.instance?.engine?.pauseRecordingDuringReplay = v
            }
        })
        col.addView(CheckBox(this).apply {
            text = "Keep screen on"
            isChecked = Prefs.keepScreenOn(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setKeepScreenOn(this@MainActivity, v); applyKeepScreenOn() }
        })
        col.addView(CheckBox(this).apply {
            text = "Start recording when the app opens"
            isChecked = Prefs.autoStart(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoStart(this@MainActivity, v) }
        })
        col.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
            text = "Recording keeps running with the screen off or in other apps; use the notification " +
                "for quick 10 s / 30 s replays. Audio comes from the phone mic, or from a wired " +
                "headset / USB-C audio adapter if one is plugged in. SAVE writes the last 240 s to " +
                "Music/RadioReplay."
        })
        return ScrollView(this).apply { addView(col) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

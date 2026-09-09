package com.coursetable.app.ui

import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.coursetable.app.R
import com.coursetable.app.data.Store
import com.coursetable.app.util.AlarmRinger

/** 自带时钟：全屏响铃界面（循环播放所选铃声，最长 3 分钟）。 */
class AlarmRingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_TEXT = "alarm_text"
        private const val AUTO_STOP_MS = 3 * 60 * 1000L
    }

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_alarm_ring)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "课程提醒"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        findViewById<TextView>(R.id.alarmTitle).text = title
        findViewById<TextView>(R.id.alarmText).text = text
        findViewById<Button>(R.id.alarmStop).setOnClickListener { stopAndFinish() }

        val store = Store(this)
        player = AlarmRinger.play(this, store.alarmRingtone)
        handler.postDelayed({ stopAndFinish() }, AUTO_STOP_MS)
    }

    private fun stopAndFinish() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        finish()
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}

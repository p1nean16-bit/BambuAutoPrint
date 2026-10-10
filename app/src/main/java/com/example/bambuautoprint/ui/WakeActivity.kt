package com.example.bambuautoprint.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import com.example.bambuautoprint.automation.ExecutionStore

/** 通过系统接口唤醒非安全锁手机，退出后由执行器启动 Handy。 */
class WakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("taskId", -1)
        if (!ExecutionStore.isRunning() || ExecutionStore.activeTaskId(this) != id) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val params = window.attributes; params.screenBrightness = .05f; window.attributes = params
        setContentView(TextView(this).apply { text = "竹竹正在唤醒手机…"; gravity = android.view.Gravity.CENTER; setBackgroundColor(android.graphics.Color.BLACK); setTextColor(android.graphics.Color.WHITE) })
        val manager = getSystemService(KeyguardManager::class.java)
        if (manager.isDeviceSecure) { finish(); return }
        manager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { handler.postDelayed({ finish() }, 1500) }
            override fun onDismissError() { finish() }
            override fun onDismissCancelled() { finish() }
        })
        handler.postDelayed({ finish() }, 7500)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}

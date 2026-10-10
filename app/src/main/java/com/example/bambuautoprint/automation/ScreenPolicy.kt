package com.example.bambuautoprint.automation

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import kotlinx.coroutines.delay

/** 用户明确开启后才尝试唤醒/锁屏；安全锁不会被绕过。 */
object ScreenPolicy {
    private var displayLock: PowerManager.WakeLock? = null
    /** 仅在执行期间保屏，覆盖 Handy 前台；任务结束必须释放，不把保屏延长到待机。 */
    @Suppress("DEPRECATION")
    @Synchronized fun hold(context: Context, minutes: Int) {
        release()
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isInteractive) return
        displayLock = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "BambuAutoPrint:ExecutionScreen").apply {
            setReferenceCounted(false)
            acquire(minutes.coerceIn(1, 1440) * 60000L + 60000L)
        }
    }
    @Synchronized fun release() { displayLock?.let { if (it.isHeld) it.release() }; displayLock = null }

    fun prefs(context: Context) = context.getSharedPreferences("screen_policy", Context.MODE_PRIVATE)
    suspend fun prepare(context: Context, taskId: Long) {
        if (!prefs(context).getBoolean("wake", false)) return
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive && !keyguard.isKeyguardLocked) return
        check(!keyguard.isDeviceSecure) { "手机有安全锁，无法无人解锁；请先自行解锁" }
        BambuAccessibilityService.wakePhone(taskId)
        repeat(40) {
            if (power.isInteractive && !keyguard.isKeyguardLocked) return
            delay(200)
        }
        error("唤醒未成功或锁屏未关闭，请检查后台弹出权限；不会自动输入密码")
    }
    suspend fun sleep(context: Context, taskId: Long) {
        release()
        if (!prefs(context).getBoolean("sleep", false)) return
        NightWatch.stop("自动化已结束，退出亮屏值守并请求锁屏")
        val accepted = runCatching { BambuAccessibilityService.lockPhone() }.getOrDefault(false)
        delay(600)
        val off = !context.getSystemService(PowerManager::class.java).isInteractive
        RunReporter.log(context, taskId, if (accepted && off) "INFO" else "WARN", "SCREEN_OFF",
            if (accepted && off) "已检测到手机息屏" else "已请求锁屏，但未确认息屏；请检查系统支持情况")
    }
}

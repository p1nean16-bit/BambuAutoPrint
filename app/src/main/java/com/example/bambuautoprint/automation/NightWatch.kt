package com.example.bambuautoprint.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.content.Context
import com.example.bambuautoprint.data.PrintTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NightWatchState(val active: Boolean = false, val taskId: Long = -1, val name: String = "", val message: String = "尚未开启夜间值守")

data class NightPowerState(val plugged: Boolean, val stayAwake: Boolean)

/** 读取用户开启的系统保屏设置；不修改系统设置，不自动恢复中断的值守。 */
object NightWatch {
    private val mutable = MutableStateFlow(NightWatchState())
    val state = mutable.asStateFlow()
    private val requests = MutableStateFlow<Long?>(null)
    val launchRequests = requests.asStateFlow()
    @Volatile var activityVisible = false
        private set
    private var service: AccessibilityService? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stop("夜间值守已达到 12 小时上限，未启动的任务将停止") }
    fun attach(value: AccessibilityService) { service = value }
    fun detach() { stop("无障碍服务中断，夜间值守已停止，请重新检查权限"); service = null }
    fun guardedTask(context: Context) = context.getSharedPreferences("night_watch", Context.MODE_PRIVATE).getLong("guard_task", -1)

    fun guardedOccurrence(context: Context, task: PrintTask): Boolean {
        val saved = context.getSharedPreferences("night_watch", Context.MODE_PRIVATE)
        return guardedTask(context) == task.id && saved.getLong("guard_trigger_at", -1) == task.triggerAtMillis
    }
    fun powerReadiness(context: Context): NightPowerState = runCatching {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugType = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val mask = Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
        NightPowerState(plugType != 0, plugType != 0 && (mask and plugType) != 0)
    }.getOrElse { NightPowerState(false, false) }

    fun start(task: PrintTask) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!mutable.value.active) { "请先退出当前值守" }
        val current = service ?: error("无障碍服务未连接")
        check(!current.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请先解锁手机" }
        check(current.getSystemService(PowerManager::class.java).isInteractive) { "夜间值守需要手机亮屏" }
        check(task.enabled && task.repeatMode == "ONCE") { "请选择启用的一次性打印计划" }
        val delay = task.triggerAtMillis - System.currentTimeMillis()
        check(delay in 30000L..12 * 60 * 60000L) { "请选择 30 秒之后、12 小时之内的打印计划" }
        val power = powerReadiness(current)
        check(power.plugged) { "请连接充电器或 USB 电源" }
        check(power.stayAwake) { "请在开发者选项开启充电时屏幕不休眠" }
        check(current.getSharedPreferences("night_watch", Context.MODE_PRIVATE).edit()
            .putLong("guard_task", task.id).putLong("guard_trigger_at", task.triggerAtMillis).commit()) { "无法保存值守保护状态" }
        mutable.value = NightWatchState(true, task.id, task.name, "值守中：保持手机解锁，停留在竹竹前台等待。请接电并保持联网。")
        handler.removeCallbacks(expire); handler.postDelayed(expire, 12 * 60 * 60000L)
        setActivityVisible(activityVisible)
        PrintNotifications.nightWatch(current, true, mutable.value.message)
    }

    fun setActivityVisible(visible: Boolean) { activityVisible = visible }
    fun requestLaunch(taskId: Long): Boolean {
        if (!activityVisible || (mutable.value.active && mutable.value.taskId != taskId)) return false
        requests.value = taskId
        return true
    }
    fun consume(taskId: Long) { if (requests.value == taskId) requests.value = null }
    fun mark(message: String) { mutable.value = mutable.value.copy(message = message) }
    fun checkScreen() {
        val current = service ?: return
        if (mutable.value.active && !powerReadiness(current).stayAwake) {
            stop("充电已断开或充电保屏设置未就绪，夜间值守已停止")
            return
        }
        if (mutable.value.active && (!current.getSystemService(PowerManager::class.java).isInteractive || current.getSystemService(KeyguardManager::class.java).isKeyguardLocked)) {
            stop("检测到息屏或锁屏，值守已停止；请亮屏解锁后重新开启")
        }
    }
    fun stop(message: String = "夜间值守已退出") {
        val wasActive = mutable.value.active
        handler.removeCallbacks(expire)
        val current = service
        requests.value = null
        mutable.value = mutable.value.copy(active = false, message = message)
        // 持久化 guard_task 保留：进程中断或手动退出后不能按旧闹钟盲目启动。
        if (wasActive && current != null) PrintNotifications.nightWatch(current, false, message)
    }
}

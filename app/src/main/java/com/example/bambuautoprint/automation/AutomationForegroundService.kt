package com.example.bambuautoprint.automation

import android.app.KeyguardManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.example.bambuautoprint.data.AppGraph
import com.example.bambuautoprint.data.PrintTask
import com.example.bambuautoprint.scheduling.TaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutomationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        if (intent?.action == ACTION_COUNTDOWN_TEST && taskId > 0L) {
            if (ExecutionStore.isRunning()) {
                serviceScope.launch {
                    RunReporter.log(this@AutomationForegroundService, taskId, "INFO", "COUNTDOWN_TEST", "倒计时闹钟已触发；另一个打印任务仍在运行")
                    PrintNotifications.result(this@AutomationForegroundService, taskId, "倒计时测试", true, "闹钟已触发；未影响正在运行的打印任务", "闹钟测试成功")
                }
                return START_NOT_STICKY
            }
            startForeground(PrintNotifications.RUNNING_ID, PrintNotifications.running(this, "10 秒倒计时测试", taskId))
            serviceScope.launch {
                RunReporter.log(this@AutomationForegroundService, taskId, "INFO", "COUNTDOWN_TEST", "倒计时闹钟已触发；未启动打印")
                PrintNotifications.result(this@AutomationForegroundService, taskId, "倒计时测试", true, "闹钟已触发，未启动打印", "闹钟测试成功")
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_EXECUTE || taskId <= 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (ExecutionStore.isRunning()) {
            if (intent.getLongExtra("groupId", -1) > 0) runCatching { com.example.bambuautoprint.scheduling.GroupCoordinator.report(this, taskId, false) }
            PrintNotifications.result(this, taskId, "任务 $taskId", false, "已有任务正在运行，本次触发已跳过")
            return START_NOT_STICKY
        }
        startForeground(PrintNotifications.RUNNING_ID, PrintNotifications.running(this, "任务 $taskId", taskId))
        serviceScope.launch { execute(taskId, intent.getBooleanExtra(EXTRA_TEST_RUN, false), intent.getLongExtra("groupId", -1), intent.getIntExtra("groupIndex", -1)) }
        return START_NOT_STICKY
    }

    private suspend fun execute(taskId: Long, isTestRun: Boolean, groupId: Long, groupIndex: Int) {
        if (!ExecutionStore.acquire(this)) {
            if (groupId > 0) runCatching { com.example.bambuautoprint.scheduling.GroupCoordinator.report(this, taskId, false) }
            val previousTaskId = ExecutionStore.activeTaskId(this)
            val detail = if (previousTaskId != null) {
                "发现上次任务 $previousTaskId 未正常结束；为防止重复打印，本次已停止。请先检查打印机状态，再在工具中清理中断记录。"
            } else "已有打印任务正在运行，本次触发已跳过。"
            RunReporter.log(this, taskId, "WARN", "LOCK", detail)
            PrintNotifications.result(this, taskId, "任务 $taskId", false, detail)
            // 持久化残留锁且当前进程没有活动执行器时，这次启动只负责报错，应退出前台服务。
            if (!ExecutionStore.isRunning()) stopSelf()
            return
        }
        try {
            val task = AppGraph.database(this).taskDao().taskById(taskId)
            val groupRun = groupId > 0 && com.example.bambuautoprint.scheduling.GroupCoordinator.permitted(this, groupId, groupIndex, taskId)
            if (task == null || (groupId > 0 && !groupRun) || (!task.enabled && !isTestRun && !groupRun)) {
                RunReporter.finish(this, taskId, false, "LOAD_TASK", "任务不存在或已禁用")
                return
            }
            check(ExecutionStore.begin(this, taskId)) { "无法持久化任务执行锁，已取消自动化" }
            if (!isTestRun && ((NightWatch.guardedOccurrence(this, task) && !NightWatch.state.value.active) ||
                    (NightWatch.state.value.active && NightWatch.state.value.taskId != taskId))) {
                RunReporter.finish(this, taskId, false, "NIGHT_WATCH", "夜间值守未就绪或这不是值守中选定的任务，本次已停止；请检查打印板后重新开启值守")
                return
            }
            if (!isTestRun && task.repeatMode.uppercase() != "ONCE") {
                // 先排下一次，避免本次执行超时或进程被杀后丢失循环计划。
                TaskScheduler(this).schedule(task)
            }
            if (!isTestRun && task.repeatMode.uppercase() == "ONCE") {
                AppGraph.database(this).taskDao().updateTask(task.copy(enabled = false))
            }
            RunReporter.log(this, taskId, "INFO", "PRECHECK", "开始检查网络、锁屏和无障碍服务")

            if (com.example.bambuautoprint.data.HandyCatalog.active(this)) {
                RunReporter.finish(this, taskId, false, "PRECHECK", "正在导入资源，请完成或取消导入后再运行打印计划")
                stopSelf()
                return
            }

            ScreenPolicy.prepare(this, taskId)
            ScreenPolicy.hold(this, if (task.adaptiveSpeedEnabled) maxOf(task.timeoutMinutes, task.speedTimeoutMinutes) else task.timeoutMinutes)
            if (isDeviceLocked()) {
                RunReporter.finish(this, taskId, false, "PRECHECK", "手机处于锁屏状态；请解锁后重试，自动化不会输入或绕过密码")
                stopSelf()
                return
            }
            if (!getSystemService(android.os.PowerManager::class.java).isInteractive) {
                RunReporter.finish(this, taskId, false, "PRECHECK", "手机已经息屏，当前 UI 自动化要求亮屏；请使用夜间值守或手动亮屏后运行")
                return
            }
            if (!hasValidatedNetwork()) {
                RunReporter.finish(this, taskId, false, "PRECHECK", "手机当前没有可用互联网连接")
                stopSelf()
                return
            }
            if (!isAccessibilityEnabled()) {
                RunReporter.finish(this, taskId, false, "PRECHECK", "请先在系统设置中开启“拓竹打印流程自动化”无障碍服务")
                stopSelf()
                return
            }
            // 手机重新打开应用后，系统绑定可能晚于 Activity 恢复；等待而非误报授权丢失。
            if (!BambuAccessibilityService.connected.value) {
                RunReporter.log(this, taskId, "INFO", "PRECHECK", "无障碍已授权，等待服务连接恢复")
                var waits = 0
                while (!BambuAccessibilityService.connected.value && waits++ < 40) delay(200)
                if (!BambuAccessibilityService.connected.value) {
                    RunReporter.finish(this, taskId, false, "PRECHECK", "无障碍授权仍开启，但服务未连接；请允许后台运行、关闭电池限制并检查系统无障碍服务状态")
                    return
                }
            }
            if (task.requireCalibration && !java.io.File(filesDir, "calibration_steps.jsonl").exists()) {
                RunReporter.finish(this, taskId, false, "PRECHECK", "此任务要求先完成一次校准录制")
                stopSelf()
                return
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(BAMBU_PACKAGE)
            if (launchIntent == null) {
                RunReporter.finish(this, taskId, false, "LAUNCH_APP", "未安装 Bambu Handy（$BAMBU_PACKAGE）")
                stopSelf()
                return
            }
            ExecutionStore.setStage(this, 0)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                check(isTestRun || !NightWatch.guardedOccurrence(this, task) || NightWatch.state.value.active) { "值守已中断，本次打印停止" }
                if (!isTestRun && NightWatch.state.value.active && NightWatch.state.value.taskId == taskId) {
                    check(NightWatch.requestLaunch(taskId)) { "值守页面不在前台，请保持竹竹前台等待；本次没有启动打印" }
                    RunReporter.log(this, taskId, "INFO", "NIGHT_WATCH", "已请求由前台竹竹页面打开 Handy")
                } else if (!isTestRun && NightWatch.activityVisible && NightWatch.requestLaunch(taskId)) {
                    RunReporter.log(this, taskId, "INFO", "LAUNCH_APP", "普通定时计划改由竹竹前台页面发起启动")
                } else {
                    BambuAccessibilityService.launchHandy(taskId)
                    RunReporter.log(this, taskId, "INFO", "LAUNCH_APP", "已通过系统绑定的无障碍服务请求启动 Handy")
                }
                RunReporter.log(this, taskId, "INFO", "LAUNCH_APP", "已请求打开 Bambu Handy；等待无障碍页面校验")
                // 无人值守不依赖通知点击；未收到 Handy 页面事件时明确结束本次执行。
                serviceScope.launch {
                    delay(30_000)
                    if (ExecutionStore.activeTaskId(this@AutomationForegroundService) == taskId &&
                        !ExecutionStore.appForeground(this@AutomationForegroundService)) {
                        RunReporter.finish(this@AutomationForegroundService, taskId, false, "LAUNCH_APP",
                            "自动启动失败：30 秒内未检测到 Handy 页面。请检查系统后台弹出界面/后台活动权限及无障碍连接；本次未自动提交打印，也不等待点击通知继续")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "后台启动 Bambu Handy 被系统拒绝", e)
                RunReporter.finish(this, taskId, false, "LAUNCH_APP", "打开 Bambu Handy 失败：${e.message}；请检查值守和前台状态")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "定时任务执行异常 taskId=$taskId", e)
            RunReporter.finish(this, taskId, false, "UNEXPECTED", e.message ?: "未预期错误")
            stopSelf()
        }
    }

    private fun isDeviceLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun hasValidatedNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(this, BambuAccessibilityService::class.java)
        return enabled.contains(component.flattenToString(), ignoreCase = true) ||
            enabled.contains(component.flattenToShortString(), ignoreCase = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ScreenPolicy.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        PrintNotifications.clearRunning(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_EXECUTE = "com.example.bambuautoprint.EXECUTE"
        const val ACTION_COUNTDOWN_TEST = "com.example.bambuautoprint.COUNTDOWN_TEST"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TEST_RUN = "extra_test_run"
        // 从用户提供的 Bambu Handy 4.1.1 APK 清单静态确认的应用包名。
        const val BAMBU_PACKAGE = "com.bambulab.creator"
        private const val TAG = "AutomationService"
    }
}

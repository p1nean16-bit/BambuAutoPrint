package com.example.bambuautoprint.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.bambuautoprint.automation.AutomationForegroundService
import com.example.bambuautoprint.automation.PrintNotifications
import com.example.bambuautoprint.automation.RunReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) {
            Log.e(TAG, "闹钟广播缺少有效 taskId")
            return
        }
        try {
            val serviceIntent = Intent(context, AutomationForegroundService::class.java)
                .setAction(if (intent.getBooleanExtra(EXTRA_COUNTDOWN_TEST, false)) {
                    AutomationForegroundService.ACTION_COUNTDOWN_TEST
                } else AutomationForegroundService.ACTION_EXECUTE)
                .putExtra(AutomationForegroundService.EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_COUNTDOWN_TEST, intent.getBooleanExtra(EXTRA_COUNTDOWN_TEST, false))
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "无法启动打印自动化前台服务，taskId=$taskId", e)
            val reason = "系统未能启动自动化服务：${e.message ?: e.javaClass.simpleName}"
            PrintNotifications.result(context, taskId, "任务 $taskId", false, reason)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                RunReporter.log(context, taskId, "ERROR", "START_SERVICE", reason)
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_COUNTDOWN_TEST = "extra_countdown_test"
        private const val TAG = "TaskAlarmReceiver"
    }
}

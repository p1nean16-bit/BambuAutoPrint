package com.example.bambuautoprint.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.bambuautoprint.data.AppGraph
import com.example.bambuautoprint.data.ExecutionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RunReporter {
    suspend fun log(
        context: Context,
        taskId: Long,
        level: String,
        stage: String,
        message: String,
        screenshotPath: String? = null
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                AppGraph.database(context).taskDao().insertLog(
                    ExecutionLog(taskId = taskId, level = level, stage = stage, message = message, screenshotPath = screenshotPath)
                )
            }.onFailure { Log.e("RunReporter", "写入任务日志失败", it) }
        }
    }

    suspend fun finish(
        context: Context,
        taskId: Long,
        success: Boolean,
        stage: String,
        message: String,
        screenshotPath: String? = null
    ) {
        try {
            val taskName = runCatching {
                AppGraph.database(context).taskDao().taskById(taskId)?.name
            }.onFailure { Log.e("RunReporter", "读取任务名称失败", it) }.getOrNull() ?: "任务 $taskId"
            log(context, taskId, if (success) "INFO" else "ERROR", stage, message, screenshotPath)
            PrintNotifications.result(context, taskId, taskName, success, message,
                if (stage == "LAYER_SPEED") if (success) "按层变速已完成" else "按层变速需要检查" else null)
        } finally {
            runCatching { com.example.bambuautoprint.scheduling.GroupCoordinator.report(context, taskId, success) }
                .onFailure { Log.e("RunReporter", "更新多任务计划状态失败", it) }
            runCatching { ScreenPolicy.sleep(context, taskId) }.onFailure { Log.e("RunReporter", "锁屏请求失败", it) }
            // 即使日志数据库不可用，也要释放执行锁和前台服务，避免后续任务永久卡住。
            PrintNotifications.clearRunning(context)
            ExecutionStore.clear(context)
            ExecutionStore.release()
            context.stopService(Intent(context, AutomationForegroundService::class.java))
        }
    }
}

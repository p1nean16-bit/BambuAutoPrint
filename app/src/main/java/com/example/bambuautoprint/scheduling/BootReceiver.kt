package com.example.bambuautoprint.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.bambuautoprint.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 系统重启、时间或时区变化后重建下一次闹钟。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val scheduler = TaskScheduler(context)
                runCatching { com.example.bambuautoprint.data.DayPlanStore.load(context) }.onFailure { Log.e(TAG, "读取日排程失败", it) }
                com.example.bambuautoprint.data.DayPlanStore.plans.value.forEach { plan ->
                    runCatching { DayPlanReminders(context).schedule(plan) }.onFailure { Log.e(TAG, "恢复日排程失败", it) }
                }
                AppGraph.database(context).taskDao().enabledTasks().forEach { task ->
                    runCatching { scheduler.schedule(task) }
                        .onFailure { Log.e(TAG, "恢复任务 ${task.id} 的闹钟失败", it) }
                }
            } catch (error: Exception) {
                Log.e(TAG, "读取计划失败，无法恢复闹钟", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { private const val TAG = "BootReceiver" }
}

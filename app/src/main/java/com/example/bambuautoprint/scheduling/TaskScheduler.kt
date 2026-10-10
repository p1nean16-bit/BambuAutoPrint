package com.example.bambuautoprint.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.bambuautoprint.data.PrintTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ExactAlarmPermissionMissing : IllegalStateException("请先授予“闹钟和提醒”精确闹钟权限")

/** 每个计划只安排下一次触发；重复任务在触发后再安排下一次，避免使用不精确的重复闹钟。 */
class TaskScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(task: PrintTask, nowMillis: Long = System.currentTimeMillis()) {
        if (!task.enabled) {
            cancel(task.id)
            return
        }
        val next = nextOccurrence(task, nowMillis) ?: run {
            // 过期的计划只取消真实执行闹钟，不影响用户刚安排的独立倒计时测试。
            alarmManager.cancel(pendingIntent(task.id))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            throw ExactAlarmPermissionMissing()
        }

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next,
                pendingIntent(task.id)
            )
        } catch (e: SecurityException) {
            throw ExactAlarmPermissionMissing()
        }
    }

    fun cancel(taskId: Long) {
        alarmManager.cancel(pendingIntent(taskId))
        alarmManager.cancel(pendingIntent(taskId, countdownTest = true))
    }

    /** 只验证闹钟是否按时触发，不会打开 Handy，也不会提交打印任务。 */
    fun scheduleCountdownTest(taskId: Long, delayMillis: Long = 10_000L) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            throw ExactAlarmPermissionMissing()
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delayMillis.coerceAtLeast(1_000L),
            pendingIntent(taskId, countdownTest = true)
        )
    }

    private fun pendingIntent(taskId: Long, countdownTest: Boolean = false): PendingIntent {
        val intent = Intent(context, TaskAlarmReceiver::class.java)
            .setAction("com.example.bambuautoprint.${if (countdownTest) "COUNTDOWN_TEST" else "RUN_TASK"}_$taskId")
            .putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(TaskAlarmReceiver.EXTRA_COUNTDOWN_TEST, countdownTest)
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** 计算本地时区下的下一次执行时间；一次性任务过期后不会重新触发。 */
        fun nextOccurrence(task: PrintTask, nowMillis: Long = System.currentTimeMillis()): Long? {
            val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
            val configured = Instant.ofEpochMilli(task.triggerAtMillis).atZone(ZoneId.systemDefault())
            val localTime = configured.toLocalTime()

            return when (task.repeatMode.uppercase()) {
                "ONCE" -> task.triggerAtMillis.takeIf { it > nowMillis }
                "DAILY" -> {
                    var candidate = LocalDateTime.of(now.toLocalDate(), localTime)
                    if (candidate.atZone(now.zone).toInstant().toEpochMilli() <= nowMillis) {
                        candidate = candidate.plusDays(1)
                    }
                    candidate.atZone(now.zone).toInstant().toEpochMilli()
                }
                "WEEKLY", "CUSTOM" -> {
                    val mask = if (task.weekdaysMask == 0) {
                        1 shl (configured.dayOfWeek.value - 1)
                    } else task.weekdaysMask
                    (0..14).asSequence().map { offset ->
                        val date: LocalDate = now.toLocalDate().plusDays(offset.toLong())
                        LocalDateTime.of(date, localTime).atZone(now.zone).toInstant().toEpochMilli()
                    }.firstOrNull { candidate ->
                        val local = Instant.ofEpochMilli(candidate).atZone(now.zone)
                        val dayBit = 1 shl (local.dayOfWeek.value - 1)
                        candidate > nowMillis && (mask and dayBit) != 0
                    }
                }
                else -> throw IllegalArgumentException("不支持的重复规则：${task.repeatMode}")
            }
        }
    }
}

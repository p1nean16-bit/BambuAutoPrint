package com.example.bambuautoprint.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.bambuautoprint.data.DayPlanStore
import com.example.bambuautoprint.data.SavedDayPlan
import com.example.bambuautoprint.automation.PrintNotifications

/** 当前升级只排时间并提醒，不启动自动点击或真实打印。 */
class DayPlanReminders(private val context: Context) {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private fun pending(plan: SavedDayPlan, index: Int, kind: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, DayPlanReminderReceiver::class.java).setAction("DAY_${plan.id}_${index}_$kind")
            .putExtra("planId", plan.id).putExtra("index", index).putExtra("kind", kind).putExtra("revision", plan.revision),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(plan: SavedDayPlan) {
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) throw ExactAlarmPermissionMissing()
        if (plan.paused) return
        val now = System.currentTimeMillis()
        plan.slots.forEachIndexed { i, s ->
            if (GroupCoordinator.status(plan, i) == "COMPLETED") return@forEachIndexed
            listOf("START" to s.startAt, "PICKUP" to s.pickupAt).forEach { (kind, time) ->
                if (time > now && (kind == "PICKUP" || !plan.automatic || GroupCoordinator.status(plan, i) == "PENDING")) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(plan, i, kind))
            }
        }
    }
    fun cancel(plan: SavedDayPlan) { plan.slots.indices.forEach { i -> listOf("START", "PICKUP").forEach { alarm.cancel(pending(plan, i, it)) } } }
}

class DayPlanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            DayPlanStore.load(context)
            val plan = DayPlanStore.plans.value.firstOrNull { it.id == intent.getLongExtra("planId", -1) } ?: return
            val index = intent.getIntExtra("index", -1)
            val slot = plan.slots.getOrNull(index) ?: return
            if (intent.getLongExtra("revision", 0) != plan.revision || plan.paused || GroupCoordinator.status(plan, index) == "COMPLETED") return
            val pickup = intent.getStringExtra("kind") == "PICKUP"
            if (!pickup && plan.automatic) {
                GroupCoordinator.launch(context, plan.id, index)
                return
            }
            val message = if (pickup) "到了预定收菜时间。请先在 Handy 确认打印完成，再收菜换板，并在多任务群发计划确认下一轮已换板。"
                else if (slot.job.templateId in plan.confirmed) "已确认换板，到计划开打时间了，请在 Handy 手动开始本项。当前排程只提醒，不自动打印。"
                else "到计划开打时间，但尚未确认本轮换板，请检查热床。当前排程只提醒，不自动打印，也不会自动补打。"
            PrintNotifications.dayPlan(context, plan.id, index, slot.job.name, pickup, message)
        } catch (error: Exception) {
            Log.e("DayPlanReminder", "多任务计划触发失败", error)
            PrintNotifications.dayPlan(context, intent.getLongExtra("planId", -1), intent.getIntExtra("index", 0), "多任务计划", false,
                "本轮未启动：${error.message}。请打开计划处理；未补打或跳过前项。")
        }
    }
}

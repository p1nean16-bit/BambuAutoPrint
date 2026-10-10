package com.example.bambuautoprint.scheduling

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.bambuautoprint.automation.*
import com.example.bambuautoprint.data.*

/** 群发计划串行执行；预计完成不能替代实际完成和换板确认。 */
object GroupCoordinator {
    fun status(plan: SavedDayPlan, index: Int) = plan.statuses[plan.slots[index].job.templateId] ?: "PENDING"
    fun current(context: Context, id: Long) = DayPlanStore.plans.value.firstOrNull { it.id == id } ?: error("多任务计划不存在")
    @Synchronized private fun update(context: Context, old: SavedDayPlan, next: SavedDayPlan) {
        DayPlanReminders(context).cancel(old)
        try {
            DayPlanStore.replace(context, next)
            DayPlanReminders(context).schedule(next)
        } catch (error: Exception) {
            runCatching { DayPlanReminders(context).cancel(next) }
            runCatching { DayPlanStore.replace(context, old); DayPlanReminders(context).schedule(old) }
            throw error
        }
    }
    @Synchronized fun launch(context: Context, id: Long, index: Int) {
        val plan = current(context, id)
        require(plan.automatic && !plan.paused) { "这组计划已暂停或仅提醒" }
        val slot = plan.slots[index]
        if (status(plan, index) !in setOf("PENDING", "WAITING_BOARD")) return
        require(ExecutionStore.activeTaskId(context) == null) { "已有运行中/中断的任务，请先处理" }
        if (slot.job.templateId !in plan.confirmed || (index > 0 && status(plan, index - 1) != "COMPLETED")) {
            update(context, plan, plan.copy(statuses = plan.statuses + (slot.job.templateId to "WAITING_BOARD")))
            PrintNotifications.dayPlan(context, id, index, slot.job.name, false, "等待你确认上一项实际完成并已换板，未启动打印")
            return
        }
        require(System.currentTimeMillis() >= slot.startAt - 2000) { "尚未到开始时间" }
        val running = plan.copy(statuses = plan.statuses + (slot.job.templateId to "RUNNING"))
        update(context, plan, running)
        try {
            ContextCompat.startForegroundService(context, Intent(context, AutomationForegroundService::class.java)
                .setAction(AutomationForegroundService.ACTION_EXECUTE)
                .putExtra(AutomationForegroundService.EXTRA_TASK_ID, plan.executionIds[index])
                .putExtra("groupId", id).putExtra("groupIndex", index))
        } catch (error: Exception) { report(context, plan.executionIds[index], false); throw error }
    }
    fun permitted(context: Context, id: Long, index: Int, taskId: Long): Boolean {
        val p = DayPlanStore.plans.value.firstOrNull { it.id == id } ?: return false
        return p.automatic && !p.paused && p.executionIds.getOrNull(index) == taskId && status(p, index) == "RUNNING" &&
            p.slots[index].job.templateId in p.confirmed && (index == 0 || status(p, index - 1) == "COMPLETED")
    }
    @Synchronized fun report(context: Context, taskId: Long, success: Boolean) {
        val plan = DayPlanStore.plans.value.firstOrNull { taskId in it.executionIds } ?: return
        val i = plan.executionIds.indexOf(taskId)
        if (status(plan, i) !in setOf("RUNNING", "SUBMITTED")) return
        // 变速监控失败不能推断实体打印失败；保留已提交状态，等待用户确认实际完成。
        if (!success && status(plan, i) == "SUBMITTED") return
        update(context, plan, plan.copy(paused = !success,
            statuses = plan.statuses + (plan.slots[i].job.templateId to if (success) "SUBMITTED" else "FAILED")))
    }
    @Synchronized fun markFailure(context: Context, id: Long, index: Int) {
        require(ExecutionStore.activeTaskId(context) == null) { "请先结束自动化或处理中断记录，并在 Handy 取消失败打印" }
        val p = current(context, id)
        require(p.slots.drop(index + 1).none { p.statuses[it.job.templateId] in setOf("RUNNING", "SUBMITTED", "COMPLETED") }) { "后续任务已有执行记录，不能重排到它们前面" }
        require(p.slots.take(index).all { p.statuses[it.job.templateId] == "COMPLETED" }) { "请先处理前面的任务" }
        update(context, p, p.copy(paused = true, confirmed = p.confirmed - p.slots[index].job.templateId,
            statuses = p.statuses + (p.slots[index].job.templateId to "FAILED")))
    }
    /** 用户已清理失败零件，并给出重打时间；保留后续顺序和原间隔，清除后续换板确认。 */
    @Synchronized fun retry(context: Context, id: Long, index: Int, start: Long, minutes: Int) {
        require(ExecutionStore.activeTaskId(context) == null) { "先检查并清理运行中/中断记录" }
        require(start > System.currentTimeMillis() + 10000 && minutes in 1..1440) { "重打时间至少在 10 秒后，时长为 1—1440 分钟" }
        val p = current(context, id)
        require(status(p, index) == "FAILED") { "请先标记本项失败，暂停后续计划" }
        val old = p.slots[index]
        val finish = start + minutes * 60000L
        val delta = maxOf(0L, finish - old.finishAt)
        val slots = p.slots.mapIndexed { i, s -> when {
            i < index -> s
            i == index -> s.copy(job = s.job.copy(minutes = minutes), boardAfter = System.currentTimeMillis(), startAt = start, finishAt = finish,
                pickupAt = finish + (s.pickupAt - s.finishAt))
            else -> s.copy(boardAfter = s.boardAfter + delta, startAt = s.startAt + delta, finishAt = s.finishAt + delta, pickupAt = s.pickupAt + delta)
        } }
        require(index == 0 || start > p.slots[index - 1].pickupAt) { "重打开始时间不能早于上一项收菜时间" }
        val future = p.slots.drop(index).map { it.job.templateId }.toSet()
        update(context, p, p.copy(slots = slots, paused = false, revision = p.revision + 1,
            confirmed = (p.confirmed - future) + old.job.templateId,
            statuses = (p.statuses - future) + (old.job.templateId to "PENDING")))
    }
    @Synchronized fun confirm(context: Context, id: Long, index: Int) {
        require(ExecutionStore.activeTaskId(context) == null) { "当前自动化仍在运行/监控，请先结束或处理中断记录" }
        var p = current(context, id)
        require(!p.paused) { "计划已暂停，请先安排失败任务重打" }
        require(index == 0 || status(p, index - 1) == "SUBMITTED") { "上一项未提交或未完成前序任务" }
        require(index == 0 || System.currentTimeMillis() >= p.slots[index].boardAfter) { "尚未到换板时间" }
        require(status(p, index) in setOf("PENDING", "WAITING_BOARD")) { "本项已有执行记录，不能再次确认启动" }
        val previousStates = if (index > 0) p.statuses + (p.slots[index - 1].job.templateId to "COMPLETED") else p.statuses
        val states = previousStates + (p.slots[index].job.templateId to "PENDING")
        p = p.copy(confirmed = p.confirmed + p.slots[index].job.templateId, statuses = states)
        // 迟到换板时整体顺延至至少 30 秒以后，不立即在旧时间下补打。
        val delay = maxOf(0L, System.currentTimeMillis() + 30000 - p.slots[index].startAt)
        if (delay > 0) p = p.copy(revision = p.revision + 1, slots = p.slots.mapIndexed { i, s -> if (i < index) s else
            s.copy(boardAfter = if (i == index) s.boardAfter else s.boardAfter + delay, startAt = s.startAt + delay, finishAt = s.finishAt + delay, pickupAt = s.pickupAt + delay) })
        if (delay > 0) {
            val laterWaiting = p.slots.drop(index + 1).filter { p.statuses[it.job.templateId] == "WAITING_BOARD" }.associate { it.job.templateId to "PENDING" }
            p = p.copy(statuses = p.statuses + laterWaiting)
        }
        update(context, current(context, id), p)
    }
    @Synchronized fun completeLast(context: Context, id: Long) {
        require(ExecutionStore.activeTaskId(context) == null) { "请先结束运行中的自动化" }
        val p = current(context, id); val i = p.slots.lastIndex
        require(status(p, i) == "SUBMITTED") { "最后一项尚未提交" }
        update(context, p, p.copy(statuses = p.statuses + (p.slots[i].job.templateId to "COMPLETED")))
    }
}

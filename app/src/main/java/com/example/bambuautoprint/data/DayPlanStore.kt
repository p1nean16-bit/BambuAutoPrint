package com.example.bambuautoprint.data

import android.content.Context
import com.example.bambuautoprint.scheduling.DayJob
import com.example.bambuautoprint.scheduling.DaySlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SavedDayPlan(val id: Long, val printer: String, val slots: List<DaySlot>, val confirmed: Set<Long> = emptySet(),
    val mode: String = "PICKUP", val automatic: Boolean = false,
    val executionIds: List<Long> = emptyList(), val statuses: Map<Long, String> = emptyMap(),
    val paused: Boolean = false, val revision: Long = 0)

/** 保存多任务时间表、执行副本和换板确认；旧版记录保持提醒模式。 */
object DayPlanStore {
    private val state = MutableStateFlow<List<SavedDayPlan>>(emptyList())
    val plans = state.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("day_plans", Context.MODE_PRIVATE)
    @Synchronized fun load(context: Context) {
        val array = JSONArray(prefs(context).getString("plans", "[]"))
        state.value = (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            val slots = p.getJSONArray("slots")
            SavedDayPlan(p.getLong("id"), p.getString("printer"), (0 until slots.length()).map { j ->
                val s = slots.getJSONObject(j)
                DaySlot(DayJob(s.getLong("templateId"), s.getString("name"), s.getString("model"), s.getInt("minutes"), s.getInt("priority")),
                    s.getLong("boardAfter"), s.getLong("start"), s.getLong("finish"), s.getLong("pickup"))
            }, p.optJSONArray("confirmed")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }.orEmpty(), p.optString("mode", "PICKUP"), p.optBoolean("automatic", false),
                p.optJSONArray("executionIds")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty(),
                p.optJSONObject("statuses")?.let { obj -> obj.keys().asSequence().associate { it.toLong() to obj.getString(it) } }.orEmpty(),
                p.optBoolean("paused", false), p.optLong("revision", 0))
        }
    }
    @Synchronized fun save(context: Context, plan: SavedDayPlan) {
        require(plan.slots.isNotEmpty()) { "不能保存空排程" }
        require(state.value.none { it.id == plan.id }) { "排程标识重复，请重新保存" }
        require(state.value.none { existing -> existing.printer.equals(plan.printer, true) &&
            plan.slots.first().boardAfter < existing.slots.last().pickupAt && existing.slots.first().boardAfter < plan.slots.last().pickupAt
        }) { "这台打印机已有重叠排程，请先移除旧排程" }
        persist(context, state.value + plan)
    }
    @Synchronized fun replace(context: Context, plan: SavedDayPlan) {
        require(state.value.any { it.id == plan.id }) { "计划不存在" }
        require(state.value.none { existing -> existing.id != plan.id && existing.printer.equals(plan.printer, true) &&
            plan.slots.first().boardAfter < existing.slots.last().pickupAt && existing.slots.first().boardAfter < plan.slots.last().pickupAt
        }) { "顺延后与另一组计划重叠，请先调整另一组" }
        persist(context, state.value.map { if (it.id == plan.id) plan else it })
    }
    @Synchronized fun remove(context: Context, id: Long) { persist(context, state.value.filterNot { it.id == id }) }
    @Synchronized fun confirm(context: Context, id: Long, templateId: Long, now: Long = System.currentTimeMillis()) {
        val plan = state.value.firstOrNull { it.id == id } ?: error("排程不存在")
        val slot = plan.slots.firstOrNull { it.job.templateId == templateId } ?: error("任务不存在")
        require(slot == plan.slots.first() || now >= slot.boardAfter) { "还没到本轮换板时间，不能提前确认" }
        require(now <= slot.startAt) { "计划开打时间已过，请重新排程；不会自动补打" }
        persist(context, state.value.map { if (it.id == id) it.copy(confirmed = it.confirmed + templateId) else it })
    }
    private fun persist(context: Context, plans: List<SavedDayPlan>) {
        val array = JSONArray()
        plans.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("printer", p.printer).put("confirmed", JSONArray(p.confirmed.toList()))
                .put("mode", p.mode).put("automatic", p.automatic).put("executionIds", JSONArray(p.executionIds))
                .put("statuses", JSONObject().apply { p.statuses.forEach { (id, status) -> put(id.toString(), status) } })
                .put("paused", p.paused).put("revision", p.revision)
                .put("slots", JSONArray().apply { p.slots.forEach { s -> put(JSONObject()
                    .put("templateId", s.job.templateId).put("name", s.job.name).put("model", s.job.model)
                    .put("minutes", s.job.minutes).put("priority", s.job.priority).put("boardAfter", s.boardAfter)
                    .put("start", s.startAt).put("finish", s.finishAt).put("pickup", s.pickupAt)) } }))
        }
        check(prefs(context).edit().putString("plans", array.toString()).commit()) { "保存排程失败" }
        state.value = plans
    }
}

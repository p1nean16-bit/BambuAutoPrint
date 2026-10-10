package com.example.bambuautoprint.scheduling

/** 优先级数字越小越优先；同优先级按用户添加顺序。所有时间为毫秒。 */
data class DayJob(val templateId: Long, val name: String, val model: String, val minutes: Int, val priority: Int)
data class DaySlot(val job: DayJob, val boardAfter: Long, val startAt: Long, val finishAt: Long, val pickupAt: Long)
data class DayPreview(val slots: List<DaySlot>, val omitted: List<DayJob>)

object DayPlanner {
    fun arrange(jobs: List<DayJob>, readyAt: Long, pickups: List<Long>, swapMinutes: Int, bufferMinutes: Int): DayPreview {
        require(jobs.isNotEmpty() && jobs.size <= 12) { "请选择 1—12 项任务" }
        require(jobs.map { it.templateId }.distinct().size == jobs.size) { "同一任务不能重复添加" }
        require(jobs.all { it.minutes in 1..1440 && it.priority in 1..99 }) { "时长需为 1—1440 分钟，优先级为 1—99" }
        require(swapMinutes in 0..120 && bufferMinutes in 0..180) { "换板预留为 0—120 分钟，启动及冷却缓冲为 0—180 分钟" }
        require(pickups.isNotEmpty() && pickups.size <= 30 && pickups.distinct().size == pickups.size) { "请填写 1—30 个不重复收菜时间" }
        val ends = pickups.sorted()
        require(ends.first() > readyAt) { "所有收菜时间必须晚于当天可开始时间" }
        // 子集动态规划。同一任务集合保留最早收菜的方案，它为后续留下最多时间。
        // 可以跳过不用的收菜点，因此长模型能跨过中午，安排到晚上再收菜。
        val count = 1 shl jobs.size
        val lastPickup = IntArray(count) { Int.MAX_VALUE }
        val parent = IntArray(count) { -1 }
        val chosen = arrayOfNulls<DaySlot>(count)
        lastPickup[0] = -1
        for (mask in 0 until count) {
            if (lastPickup[mask] == Int.MAX_VALUE) continue
            val boardAfter = if (mask == 0) readyAt else ends[lastPickup[mask]]
            val earliest = boardAfter + if (mask == 0) 0L else swapMinutes * 60000L
            jobs.indices.filter { mask and (1 shl it) == 0 }.forEach { jobIndex ->
                val job = jobs[jobIndex]
                val pickupIndex = ((lastPickup[mask] + 1) until ends.size).firstOrNull { index ->
                    ends[index] - (job.minutes + bufferMinutes) * 60000L >= earliest
                } ?: return@forEach
                val next = mask or (1 shl jobIndex)
                if (pickupIndex < lastPickup[next]) {
                    lastPickup[next] = pickupIndex; parent[next] = mask
                    val finish = ends[pickupIndex] - bufferMinutes * 60000L
                    chosen[next] = DaySlot(job, boardAfter, finish - job.minutes * 60000L, finish, ends[pickupIndex])
                }
            }
        }
        val priorities = jobs.map { it.priority }.distinct().sorted()
        fun better(a: Int, b: Int): Boolean {
            for (priority in priorities) {
                val ac = jobs.indices.count { a and (1 shl it) != 0 && jobs[it].priority == priority }
                val bc = jobs.indices.count { b and (1 shl it) != 0 && jobs[it].priority == priority }
                if (ac != bc) return ac > bc
            }
            val am = jobs.indices.filter { a and (1 shl it) != 0 }.sumOf { jobs[it].minutes }
            val bm = jobs.indices.filter { b and (1 shl it) != 0 }.sumOf { jobs[it].minutes }
            return am > bm || (am == bm && lastPickup[a] < lastPickup[b])
        }
        var best = 0
        for (mask in 1 until count) if (lastPickup[mask] != Int.MAX_VALUE && better(mask, best)) best = mask
        var cursor = best
        val slots = mutableListOf<DaySlot>()
        while (cursor != 0) { slots += chosen[cursor]!!; cursor = parent[cursor] }
        return DayPreview(slots.asReversed(), jobs.filterIndexed { index, _ -> best and (1 shl index) == 0 })
    }
}

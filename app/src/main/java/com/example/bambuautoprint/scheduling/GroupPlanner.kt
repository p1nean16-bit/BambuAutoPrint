package com.example.bambuautoprint.scheduling

/** 手动顺序模式：间隔从上一项预计收菜（完成加冷却缓冲）时开始计算，跨天使用完整时间戳。 */
object GroupPlanner {
    fun manual(jobs: List<DayJob>, start: Long, gaps: List<Int>, buffer: Int): DayPreview {
        require(jobs.size in 1..12 && jobs.map { it.templateId }.distinct().size == jobs.size) { "请选择 1—12 个不同任务" }
        require(jobs.all { it.minutes in 1..1440 } && buffer in 0..180 && gaps.size == jobs.size && gaps.all { it in 0..1440 }) { "打印时长为 1—1440 分钟，间隔为 0—1440 分钟，缓冲为 0—180 分钟" }
        var next = start
        var board = start
        return DayPreview(jobs.mapIndexed { i, job ->
            if (i > 0) next = board + gaps[i] * 60000L
            val finish = next + job.minutes * 60000L
            val pickup = finish + buffer * 60000L
            DaySlot(job, board, next, finish, pickup).also { board = pickup }
        }, emptyList())
    }
}

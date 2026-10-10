package com.example.bambuautoprint.automation

/** 不依赖 Android 的规则，便于校验层数格式和临界层行为。 */
data class LayerReading(val current: Int, val total: Int)

object LayerSpeedRules {
    val percentages = mapOf("静音" to 50, "标准" to 100, "运动" to 124, "狂暴" to 166)
    private val aliases = mapOf("silent" to "静音", "standard" to "标准", "sport" to "运动", "ludicrous" to "狂暴")
    fun mode(label: String): String? {
        val clean = label.trim().lowercase().replace(Regex("\\s*\\(?\\d+%\\)?$"), "").trim()
        return percentages.keys.firstOrNull { it == clean } ?: aliases[clean]
    }

    fun parse(values: List<String>): LayerReading? {
        val candidates = values.flatMapIndexed { index, value ->
            // 只拼接紧邻的“层数”标签，防止把 AMS 1/4 误识别为打印层数。
            listOf(value) + if (value.trim().matches(Regex("(?:当前层(?:数)?|层数|层|current layer|layers?)[:：]?", RegexOption.IGNORE_CASE)))
                listOf(value + " " + values.getOrNull(index + 1).orEmpty()) else emptyList()
        }
        val patterns = listOf(
            Regex("(?:当前层(?:数)?|层数|层|current layer|layers?)\\s*[:：]?\\s*(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s*/\\s*(\\d+)\\s*(?:层|layers?)", RegexOption.IGNORE_CASE)
        )
        val readings = candidates.mapNotNull { text ->
            val match = patterns.firstNotNullOfOrNull { it.find(text) } ?: return@mapNotNull null
            val current = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val total = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            LayerReading(current, total).takeIf { total > 0 && current in 0..total }
        }.distinct()
        return readings.singleOrNull() // 同页有多个不一致的任务层数时不猜测。
    }
    fun desired(layer: Int, threshold: Int, before: String, after: String) = if (layer >= threshold) after else before
    fun valid(threshold: Int, before: String, after: String, timeout: Int) = threshold in 1..100000 && timeout in 1..1440 &&
        (percentages[before] ?: 0) > (percentages[after] ?: Int.MAX_VALUE)
}

package com.example.bambuautoprint.ui

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.example.bambuautoprint.automation.ExecutionStore
import com.example.bambuautoprint.data.*
import com.example.bambuautoprint.scheduling.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

private data class JobInput(val id: Long, val minutes: String = "120", val priority: String = "1", val gap: String = "10")
private val clockFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private fun clock(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(clockFormat)

/** 本页只管理日排程与提醒，自动执行待手机端兼容性确认后再接入。 */
@Composable fun DayPlanScreen(context: Context, tasks: List<PrintTask>, tell: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val saved by DayPlanStore.plans.collectAsState()
    val initialTime = remember { LocalDateTime.now().plusMinutes(10) }
    var date by remember { mutableStateOf(initialTime.toLocalDate()) }
    var ready by remember { mutableStateOf(initialTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var pickupText by remember { mutableStateOf(listOf("12:00", "18:00", "22:00").filter { LocalTime.parse(it) > initialTime.toLocalTime() }.joinToString("，")) }
    var swap by remember { mutableStateOf("10") }
    var buffer by remember { mutableStateOf("15") }
    var printer by remember { mutableStateOf(tasks.firstOrNull()?.printerIdentifier.orEmpty()) }
    val jobs = remember { mutableStateListOf<JobInput>() }
    var preview by remember { mutableStateOf<DayPreview?>(null) }
    var mode by remember { mutableStateOf("PICKUP") }
    var retrying by remember { mutableStateOf<Pair<SavedDayPlan, Int>?>(null) }
    var retryDelay by remember { mutableStateOf("2") }
    var retryMinutes by remember { mutableStateOf("120") }
    var completePlan by remember { mutableStateOf<SavedDayPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SavedDayPlan?>(null) }
    var confirming by remember { mutableStateOf<Pair<SavedDayPlan, DaySlot>?>(null) }

    fun calculate(): DayPreview {
        require(printer.isNotBlank()) { "请先选择打印机" }
        fun time(value: String): Long {
            require(value.trim().matches(Regex("\\d{2}:\\d{2}"))) { "时间请用 HH:mm 格式，例如 08:00" }
            return date.atTime(LocalTime.parse(value.trim())).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val start = time(ready)
        require(start > System.currentTimeMillis()) { "可开始时间已过，请设置未来时间" }
        val pickups = if (mode == "MANUAL") emptyList() else pickupText.split(Regex("[,，;；\\s]+")).filter(String::isNotBlank).map(::time)
        val definitions = jobs.map { entry ->
            val task = tasks.firstOrNull { it.id == entry.id } ?: error("已选原计划已被删除，请重新添加")
            require(task.printerIdentifier == printer) { "同一份排程只能使用一台打印机" }
            DayJob(task.id, task.name, task.modelName, entry.minutes.toIntOrNull() ?: 0, entry.priority.toIntOrNull() ?: 0)
        }
        return if (mode == "MANUAL") GroupPlanner.manual(definitions, start, jobs.map { it.gap.toIntOrNull() ?: -1 }, buffer.toIntOrNull() ?: -1)
            else DayPlanner.arrange(definitions, start, pickups, swap.toIntOrNull() ?: -1, buffer.toIntOrNull() ?: -1)
    }
    fun save() {
        scope.launch {
            busy = true
            var created: SavedDayPlan? = null
            var previous = emptyList<PrintTask>()
            val cloneIds = mutableListOf<Long>()
            try {
                val calculated = calculate()
                check(calculated == preview) { "配置已经改变，请重新生成时间表" }
                check(calculated.slots.isNotEmpty()) { "没有可安排的任务，请增加空档或调整时长" }
                check(Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) { "请先在“我的”开启精确闹钟" }
                check(NotificationManagerCompat.from(context).areNotificationsEnabled()) { "请先开启结果通知，才能收到开打和收菜提醒" }
                check(!com.example.bambuautoprint.automation.NightWatch.state.value.active) { "请先退出单任务夜间值守，再保存多任务计划" }
                check(ExecutionStore.activeTaskId(context) == null) { "请先处理运行中或中断的打印任务" }
                withContext(Dispatchers.IO) {
                    val db = AppGraph.database(context)
                    val dao = db.taskDao()
                    previous = jobs.map { entry -> dao.taskById(entry.id) ?: error("原计划已不存在") }
                    require(previous.all { it.printerIdentifier == printer }) { "原计划设备已修改，请重新生成时间表" }
                    require(calculated.slots.all { s -> previous.any { t -> t.id == s.job.templateId && t.name == s.job.name && t.modelName == s.job.model } }) { "原计划模型已修改，请重新生成时间表" }
                    val conflict = dao.enabledTasks().firstOrNull { t -> t.printerIdentifier == printer && previous.none { it.id == t.id } &&
                        TaskScheduler.nextOccurrence(t, calculated.slots.first().boardAfter - 1)?.let { it < calculated.slots.last().pickupAt } == true }
                    require(conflict == null) { "这台打印机还有独立定时“${conflict?.name}”，请先停用再保存日排程" }
                    calculated.slots.forEach { slot ->
                        val template = previous.first { it.id == slot.job.templateId }
                        cloneIds += dao.insertTask(template.copy(id = 0, enabled = false, repeatMode = "ONCE", triggerAtMillis = slot.startAt))
                    }
                    val plan = SavedDayPlan(System.currentTimeMillis(), printer, calculated.slots, mode = mode, automatic = true, executionIds = cloneIds.toList())
                    DayPlanStore.save(context, plan); created = plan
                    // 已选但放不下的任务也停用旧定时，避免它们在日排程之外重复启动。
                    db.withTransaction { previous.forEach { dao.updateTask(it.copy(enabled = false)) } }
                    previous.forEach { TaskScheduler(context).cancel(it.id) }
                    DayPlanReminders(context).schedule(plan)
                }
                preview = null
                tell("多任务群发计划已保存；确认换板后按时间自动尝试打印，原独立定时已停用")
            } catch (error: Exception) {
                withContext(Dispatchers.IO) {
                    created?.let { plan -> runCatching { DayPlanReminders(context).cancel(plan) }; runCatching { DayPlanStore.remove(context, plan.id) } }
                    cloneIds.forEach { id -> runCatching { AppGraph.database(context).taskDao().deleteTaskById(id) } }
                    if (created != null) runCatching {
                        val db = AppGraph.database(context)
                        db.withTransaction { previous.forEach { db.taskDao().updateTask(it) } }
                        previous.forEach { TaskScheduler(context).schedule(it) }
                    }
                }
                tell("保存失败：${error.message}")
            } finally { busy = false }
        }
    }

    SoftCard(color = Peach) {
        Text("多任务群发计划", style = MaterialTheme.typography.titleLarge)
        Text("选择收菜时间自动排序，或手动调整顺序与间隔。确认打印板后按计划尝试打印；预计时长手动填写。失败暂停，确认重打后顺延后续。", style = MaterialTheme.typography.bodySmall)
    }
    SoftCard {
        SectionTitle("01  安排方式与开始时间")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == "PICKUP", { mode = "PICKUP"; preview = null }, label = { Text("按收菜时间") })
            FilterChip(mode == "MANUAL", { mode = "MANUAL"; preview = null }, label = { Text("手动顺序与间隔") })
        }
        OutlinedButton(onClick = { DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); preview = null }, date.year, date.monthValue - 1, date.dayOfMonth).show() }, modifier = Modifier.fillMaxWidth()) { Text(date.toString()) }
        PlannerChoice("打印机", printer, tasks.map { it.printerIdentifier }.distinct()) { if (printer != it) { printer = it; jobs.clear(); preview = null } }
        PlannerInput("当天最早可开始时间（HH:mm）", ready) { ready = it; preview = null }
        if (mode == "PICKUP") PlannerInput("方便收菜的时间（如 12:00，18:00，22:00）", pickupText) { pickupText = it; preview = null }
        if (mode == "PICKUP") PlannerInput("每次换板预留分钟", swap) { swap = it; preview = null }
        PlannerInput("每项启动与冷却缓冲分钟", buffer) { buffer = it; preview = null }
    }
    SoftCard {
        SectionTitle("02  今天想打印哪些作品")
        Text("从已配置好的计划中选取，最多 12 项。收菜模式按优先级安排；手动模式按列表顺序安排，间隔从上一项预计收菜时开始计算。", style = MaterialTheme.typography.bodySmall)
        val choices = tasks.filter { it.printerIdentifier == printer && jobs.none { j -> j.id == it.id } }
        PlannerChoice("添加一项", "选择已有打印计划", choices.map { "${it.name} · #${it.id}" }) { picked ->
            choices.firstOrNull { "${it.name} · #${it.id}" == picked }?.let { if (jobs.size < 12) { jobs += JobInput(it.id); preview = null } else tell("每份日排程最多 12 项") }
        }
        if (tasks.isEmpty()) Text("请先到资源库选择模型，创建并保存打印计划，再来填写时长。", style = MaterialTheme.typography.bodySmall)
        jobs.toList().forEachIndexed { index, entry ->
            HorizontalDivider()
            Text(tasks.firstOrNull { it.id == entry.id }?.name ?: "原计划已删除", style = MaterialTheme.typography.titleMedium)
            Text(tasks.firstOrNull { it.id == entry.id }?.modelName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Mint)
            PlannerInput("预计打印分钟（例如 150＝2.5 小时）", entry.minutes) { jobs[index] = entry.copy(minutes = it); preview = null }
            if (mode == "PICKUP") PlannerInput("优先级（1—99，数字越小越优先）", entry.priority) { jobs[index] = entry.copy(priority = it); preview = null }
            else {
                if (index > 0) PlannerInput("上一项预计收菜后间隔分钟", entry.gap) { jobs[index] = entry.copy(gap = it); preview = null }
                Row {
                    TextButton(onClick = { val old = jobs[index - 1]; jobs[index - 1] = entry; jobs[index] = old; preview = null }, enabled = index > 0) { Text("上移") }
                    TextButton(onClick = { val old = jobs[index + 1]; jobs[index + 1] = entry; jobs[index] = old; preview = null }, enabled = index < jobs.lastIndex) { Text("下移") }
                }
            }
            TextButton(onClick = { jobs.remove(entry); preview = null }) { Text("移除本项") }
        }
        Button(onClick = { runCatching { calculate() }.onSuccess { preview = it }.onFailure { tell(it.message ?: "无法生成排程") } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (mode == "PICKUP") "自动排序并生成时间表" else "按当前顺序生成时间表") }
    }
    preview?.let { p -> SoftCard(color = SoftMint) {
        SectionTitle("03  预计时间表")
        p.slots.forEachIndexed { i, s ->
            Text("${i + 1}. ${s.job.name} · 优先级 ${s.job.priority}", style = MaterialTheme.typography.titleMedium)
            Text("${clock(s.startAt)} 开打 → ${clock(s.finishAt)} 预计完成 → ${clock(s.pickupAt)} 收菜")
        }
        if (p.omitted.isNotEmpty()) Text("当天未安排：${p.omitted.joinToString { it.name }}。在优先级取舍后，没有足够空档。")
        Text("保存后会停用所有已选原计划的独立定时（包括未安排项），避免按旧时间打印。此组将尝试自动打印；每轮必须确认已换板。预计完成不等于实际完成，迟到确认会顺延本轮及后续时间。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = ::save, enabled = !busy && p.slots.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存…" else "保存多任务计划并停用原定时") }
    } }
    SectionTitle("已保存的多任务群发计划")
    if (saved.isEmpty()) Text("还没有多任务计划。")
    saved.sortedByDescending { it.id }.forEach { plan -> SoftCard {
        Text("${if (plan.mode == "MANUAL") "手动顺序" else "收菜时间"} · ${plan.printer}", style = MaterialTheme.typography.titleMedium)
        Text(if (!plan.automatic) "旧版提醒计划，不自动打印" else if (plan.paused) "已暂停，请处理失败任务" else "按计划执行 · 每轮确认换板", color = Mint)
        plan.slots.forEachIndexed { index, slot ->
            HorizontalDivider()
            Text("${index + 1}. ${slot.job.name}", style = MaterialTheme.typography.titleMedium)
            Text("${clock(slot.startAt)} 开打 → ${clock(slot.finishAt)} 预计完成\n${clock(slot.pickupAt)} 预计收菜", style = MaterialTheme.typography.bodySmall)
            val phase = GroupCoordinator.status(plan, index)
            val label = when (phase) { "RUNNING" -> "自动化执行中"; "SUBMITTED" -> "已提交 · 等待实际完成"; "COMPLETED" -> "已确认完成"; "FAILED" -> "失败 · 等待重打"; "WAITING_BOARD" -> "等待已换板确认"; else -> "等待计划时间" }
            Text(label, color = Mint)
            if (slot.job.templateId !in plan.confirmed && phase in setOf("PENDING", "WAITING_BOARD")) OutlinedButton(onClick = { confirming = plan to slot }, enabled = !plan.paused) { Text("已换板，准备本轮") }
            if (plan.automatic && phase != "COMPLETED") TextButton(onClick = {
                runCatching { GroupCoordinator.markFailure(context, plan.id, index) }
                    .onSuccess { retryMinutes = slot.job.minutes.toString(); retryDelay = "2"; retrying = plan to index }
                    .onFailure { tell(it.message ?: "无法标记失败") }
            }) { Text(if (phase == "FAILED") "安排重打并顺延" else "此项失败／取消，安排重打") }
        }
        if (plan.automatic && GroupCoordinator.status(plan, plan.slots.lastIndex) == "SUBMITTED") OutlinedButton(onClick = { completePlan = plan }) { Text("最后一项已实际完成，结束这组") }
        TextButton(onClick = { removing = plan }) { Text("删除这组计划") }
    } }
    completePlan?.let { plan -> AlertDialog(onDismissRequest = { completePlan = null }, title = { Text("确认实际完成") },
        text = { Text("请确认最后一项已在打印机上完成，不能仅根据预计时间判断。") },
        confirmButton = { TextButton(onClick = { completePlan = null; runCatching { GroupCoordinator.completeLast(context, plan.id) }.onFailure { tell(it.message.orEmpty()) } }) { Text("已确认完成") } },
        dismissButton = { TextButton(onClick = { completePlan = null }) { Text("取消") } }) }
    retrying?.let { (plan, index) -> AlertDialog(onDismissRequest = { retrying = null }, title = { Text("重打并顺延后续") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请先在 Handy 取消失败打印、清理零件并换板。下面的确认表示热床已准备好。后续顺序和间隔保留，开打及收菜时间一起顺延；其他独立计划不变。")
            PlannerInput("从现在起多少分钟后重打", retryDelay) { retryDelay = it }
            PlannerInput("本次预计打印分钟", retryMinutes) { retryMinutes = it }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                val delay = retryDelay.toIntOrNull() ?: error("请输入分钟")
                require(delay in 1..1440) { "等待时间为 1—1440 分钟" }
                GroupCoordinator.retry(context, plan.id, index, System.currentTimeMillis() + delay * 60000L, retryMinutes.toIntOrNull() ?: 0)
            }.onSuccess { retrying = null; tell("重打已安排，后续时间已顺延，下一项仍需已换板确认") }.onFailure { tell(it.message ?: "重排失败") }
        }) { Text("已清理换板，确认重打") } },
        dismissButton = { TextButton(onClick = { retrying = null }) { Text("暂不重打，保持暂停") } }) }
    confirming?.let { (plan, s) -> AlertDialog(onDismissRequest = { confirming = null }, title = { Text("确认本轮打印板已准备好") },
        text = { Text("请先在 Handy 确认上一项已完成，再移除零件、换板并检查热床。确认后，自动群发计划会在安排时间尝试启动本轮；晚于原时间则顺延。旧版提醒计划仍只提醒。") },
        confirmButton = { TextButton(onClick = { confirming = null; runCatching { if (plan.automatic) GroupCoordinator.confirm(context, plan.id, plan.slots.indexOf(s)) else DayPlanStore.confirm(context, plan.id, s.job.templateId) }.onSuccess { tell("已确认本轮准备好，请查看最新时间表") }.onFailure { tell(it.message ?: "确认失败") } }) { Text("已检查，确认") } },
        dismissButton = { TextButton(onClick = { confirming = null }) { Text("取消") } }) }
    removing?.let { plan -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除这组计划？") },
        text = { Text("将取消这一组的开打及收菜提醒。已提交到打印机的任务不会被取消。已停用的原计划由你按需重新启用。") },
        confirmButton = { TextButton(onClick = {
            removing = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        check(plan.executionIds.none { it == ExecutionStore.activeTaskId(context) }) { "本组自动化正在执行，请先结束" }
                        DayPlanReminders(context).cancel(plan)
                        DayPlanStore.remove(context, plan.id)
                        plan.executionIds.forEach { id -> AppGraph.database(context).taskDao().deleteTaskById(id) }
                    }
                }.onSuccess { tell("多任务计划已删除，实体打印不受影响") }.onFailure { tell("删除失败：${it.message}") }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

@Composable private fun PlannerInput(label: String, value: String, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
@Composable private fun PlannerChoice(label: String, value: String, choices: List<String>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mint)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value.ifBlank { "请选择" }); Text(" ⌄") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                if (choices.isEmpty()) DropdownMenuItem(text = { Text("暂无可选计划，请先创建") }, onClick = { expanded = false }, enabled = false)
                choices.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; changed(option) }) }
            }
        }
    }
}

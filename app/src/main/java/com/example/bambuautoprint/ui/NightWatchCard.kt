package com.example.bambuautoprint.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.bambuautoprint.automation.*
import com.example.bambuautoprint.data.*
import com.example.bambuautoprint.scheduling.TaskScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun NightWatchCard(context: Context, tasks: List<PrintTask>, tell: (String) -> Unit, resumeTick: Int) {
    val state by NightWatch.state.collectAsState()
    val connected by BambuAccessibilityService.connected.collectAsState()
    val power = remember(resumeTick, state.active, state.message) { NightWatch.powerReadiness(context) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableLongStateOf(-1) }
    var prepared by remember(selected) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val choices = tasks.filter { it.enabled && it.repeatMode == "ONCE" && it.triggerAtMillis > now && it.triggerAtMillis <= now + 12 * 60 * 60000L }
    val task = choices.firstOrNull { it.id == selected }
    val format = remember { DateTimeFormatter.ofPattern("MM月dd日 HH:mm") }
    SoftCard(color = SoftMint) {
        SectionTitle("夜间值守", "充电保屏 · 前台等待 · 到点尝试打印")
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (state.active) {
            Text("本次任务：${state.name}", style = MaterialTheme.typography.titleMedium)
            Text("请保持竹竹在前台等待，到点会自动转到 Handy。屏幕始终亮着；必须持续接电，不要按电源键、划掉应用或强行停止。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch {
                    busy = true
                    val id = state.taskId
                    // 先退出并保留中断保护，再停用闹钟。即使停用失败，也不会盲目下发打印。
                    NightWatch.stop("已手动退出值守；正在停用选定计划")
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val dao = AppGraph.database(context).taskDao()
                            dao.taskById(id)?.let { dao.updateTask(it.copy(enabled = false)) }
                            TaskScheduler(context).cancel(id)
                        }
                    }.onSuccess { tell("值守已退出，选定计划已停用；已经开始的打印继续") }
                        .onFailure { tell("值守已退出，停用计划失败：${it.message}；中断保护仍保留") }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("退出值守并停用选定计划") }
        } else {
            Text("${if (power.plugged) "✓" else "□"} 已接电    ${if (power.stayAwake) "✓" else "□"} 充电时不休眠", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                            .onFailure { tell("无法打开系统设置：${it.message}") }
                        tell("请在关于手机中开启开发者选项，再打开充电时屏幕不休眠")
                    }
            }, modifier = Modifier.fillMaxWidth()) { Text("打开开发者选项") }
            Text("开启“充电时屏幕不休眠”（有些手机叫保持唤醒），返回本页检查。请保持解锁，并自行把系统亮度调低；竹竹只调暗自己的窗口。", style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(task?.let { "${it.name} · ${Instant.ofEpochMilli(it.triggerAtMillis).atZone(ZoneId.systemDefault()).format(format)}" } ?: "选择未来 12 小时内的一次性计划") }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    if (choices.isEmpty()) DropdownMenuItem(text = { Text("请先创建并启用一次性计划") }, onClick = { expanded = false }, enabled = false)
                    choices.forEach { item -> DropdownMenuItem(text = { Text("${item.name} · ${Instant.ofEpochMilli(item.triggerAtMillis).atZone(ZoneId.systemDefault()).format(format)}") }, onClick = { selected = item.id; expanded = false }) }
                }
            }
            task?.let { Text("${it.modelName}\n${it.printerIdentifier}", style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(prepared, { prepared = it })
                Text("我已清空打印板、检查耗材和设备，确认到点可真实打印", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            Text("值守期间只允许选定任务自动启动，其他计划触发时会停止并记录。无障碍、精确闹钟、通知和网络需就绪。日排程当前仍只提醒，不会因开启值守自动转成打印任务。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                scope.launch {
                    busy = true
                    runCatching {
                        check(connected) { "请先开启无障碍服务并等待连接" }
                        check(prepared) { "请先确认打印板和耗材准备好" }
                        check(ExecutionStore.activeTaskId(context) == null && !HandyCatalog.active(context)) { "请先处理运行中任务、中断记录或资源导入" }
                        check(Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) { "请先开启精确闹钟" }
                        check(NotificationManagerCompat.from(context).areNotificationsEnabled()) { "请先开启结果通知" }
                        check(context.packageManager.getLaunchIntentForPackage(AutomationForegroundService.BAMBU_PACKAGE) != null) { "请先安装并登录 Handy" }
                        val chosen = task ?: error("请选择计划")
                        val current = withContext(Dispatchers.IO) {
                            AppGraph.database(context).taskDao().taskById(chosen.id) ?: error("计划已删除")
                        }
                        check(current == chosen) { "计划已修改，请重新选择并检查" }
                        withContext(Dispatchers.IO) { TaskScheduler(context).schedule(current) }
                        NightWatch.start(current)
                        tell("夜间值守已开启，请停留在竹竹前台等待")
                    }.onFailure { tell("开启失败：${it.message}") }
                    busy = false
                }
            }, enabled = !busy && task != null && prepared, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在准备…" else "开启夜间值守") }
        }
    }
}

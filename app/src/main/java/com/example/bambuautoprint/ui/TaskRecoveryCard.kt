package com.example.bambuautoprint.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bambuautoprint.automation.ExecutionStore
import com.example.bambuautoprint.data.*
import kotlinx.coroutines.delay

/** 首页与计划页共用，不再把中断处理藏在工具列表里。 */
@Composable fun TaskRecoveryCard(context: Context, tasks: List<PrintTask>, logs: List<ExecutionLog>, recover: () -> Unit, showLogs: () -> Unit, groups: () -> Unit) {
    var active by remember { mutableStateOf(ExecutionStore.activeTaskId(context)) }
    var running by remember { mutableStateOf(ExecutionStore.isRunning()) }
    LaunchedEffect(Unit) { while (true) { active = ExecutionStore.activeTaskId(context); running = ExecutionStore.isRunning(); delay(1500) } }
    val last = logs.firstOrNull { it.stage !in setOf("SCREEN_OFF", "COUNTDOWN_TEST") }
    if (active != null) SoftCard(color = Peach) {
        SectionTitle(if (running) "当前任务正在执行" else "发现中断任务 · 需要处理")
        Text(tasks.firstOrNull { it.id == active }?.name ?: "任务 #$active")
        Text(if (running) "自动化运行/变速监控中，不能清理执行锁。实体打印是否结束请在 Handy 查看。" else "先检查打印机是否正在打印或排队，再清理中断记录，避免重复提交。", style = MaterialTheme.typography.bodySmall)
        if (!running) Button(onClick = recover, modifier = Modifier.fillMaxWidth()) { Text("处理上次中断的任务") }
        TextButton(onClick = showLogs) { Text("查看执行日志") }
    } else if (last?.level == "ERROR") SoftCard(color = Peach) {
        SectionTitle("最近一次任务失败")
        Text(last.message, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = showLogs) { Text("查看原因") }
            TextButton(onClick = groups) { Text("群发计划：安排重打") }
        }
    }
}

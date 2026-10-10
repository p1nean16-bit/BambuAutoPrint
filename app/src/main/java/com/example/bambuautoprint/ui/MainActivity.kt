package com.example.bambuautoprint.ui

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.view.WindowManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.bambuautoprint.automation.*
import com.example.bambuautoprint.data.*
import com.example.bambuautoprint.scheduling.TaskScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var landing by mutableIntStateOf(0)
    private var requestedLogs by mutableIntStateOf(0)
    private var requestedPlanner by mutableIntStateOf(0)
    private var requestedNightWatch by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HandyCatalog.load(this)
        runCatching { DayPlanStore.load(this) }.onFailure { Log.e("DayPlan", "读取日排程失败", it) }
        handleIntent(intent)
        setContent { CartoonTheme { PrinterApp(this, resumeTick, landing, requestedLogs, requestedPlanner, requestedNightWatch) } }
    }
    override fun onResume() { super.onResume(); NightWatch.setActivityVisible(true); resumeTick++ }
    override fun onPause() { NightWatch.setActivityVisible(false); super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); resumeTick++ }
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_IMPORT_FINISHED) landing = 2
        if (intent?.action == ACTION_VIEW_LOGS) { landing = 3; requestedLogs++ }
        if (intent?.action == ACTION_DAY_PLAN) { landing = 1; requestedPlanner++ }
        if (intent?.action == ACTION_NIGHT_WATCH) { landing = 3; requestedNightWatch++ }
        if (intent?.action == ACTION_CONTINUE_AUTOMATION) {
            val id = intent.getLongExtra(AutomationForegroundService.EXTRA_TASK_ID, -1L)
            if (!ExecutionStore.isRunning() || ExecutionStore.activeTaskId(this) != id) {
                PrintNotifications.clearRunning(this)
                landing = 3; requestedLogs++
                Toast.makeText(this, "任务已结束，请查看执行结果", Toast.LENGTH_LONG).show()
                return
            }
            runCatching { startActivity(handyIntent(this)) }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        }
    }
    companion object {
        const val ACTION_CONTINUE_AUTOMATION = "com.example.bambuautoprint.CONTINUE_AUTOMATION"
        const val ACTION_IMPORT_FINISHED = "com.example.bambuautoprint.IMPORT_FINISHED"
        const val ACTION_VIEW_LOGS = "com.example.bambuautoprint.VIEW_LOGS"
        const val ACTION_DAY_PLAN = "com.example.bambuautoprint.DAY_PLAN"
        const val ACTION_NIGHT_WATCH = "com.example.bambuautoprint.NIGHT_WATCH"
    }
}

private fun accessibilityReady(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
    val component = ComponentName(context, BambuAccessibilityService::class.java)
    return enabled.any { it.equals(component.flattenToString(), true) || it.equals(component.flattenToShortString(), true) }
}
private fun alarmReady(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
private fun notificationReady(context: Context) = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
private fun handyIntent(context: Context): Intent = context.packageManager.getLaunchIntentForPackage(AutomationForegroundService.BAMBU_PACKAGE)
    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: error("没有找到 Bambu Handy，请先安装并登录")
private val dateFormat = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")
private fun formatTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFormat)
private fun repeatName(mode: String) = when (mode) { "DAILY" -> "每天"; "WEEKLY" -> "每周"; "CUSTOM" -> "指定星期"; else -> "仅一次" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PrinterApp(activity: MainActivity, resumeTick: Int, landing: Int, requestedLogs: Int, requestedPlanner: Int, requestedNightWatch: Int) {
    val dao = remember { AppGraph.database(activity).taskDao() }
    val allTasks by dao.observeTasks().collectAsState(emptyList())
    val groups by DayPlanStore.plans.collectAsState()
    val groupTaskIds = groups.flatMap { it.executionIds }.toSet()
    val tasks = allTasks.filter { it.id !in groupTaskIds }
    val resources by HandyCatalog.resources.collectAsState()
    val importStatus by HandyCatalog.status.collectAsState()
    val logs by dao.observeLogs().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(landing) }
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PrintTask?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showDayPlanner by remember { mutableStateOf(false) }
    // 各页面独立保存滚动位置，避免日志页高度变化重置“我的”的位置。
    val homeScroll = rememberScrollState()
    val plansScroll = rememberScrollState()
    val libraryScroll = rememberScrollState()
    val profileScroll = rememberScrollState()
    val logsScroll = rememberScrollState()
    val editorScroll = rememberScrollState()
    val plannerScroll = rememberScrollState()
    val manualScroll = rememberScrollState()
    val pageKey = when { showManual -> "manual"; showDayPlanner -> "planner"; editor -> "editor"; showLogs -> "logs"; else -> "tab-$tab" }
    val pageScroll = when {
        showManual -> manualScroll
        showDayPlanner -> plannerScroll
        editor -> editorScroll
        showLogs -> logsScroll
        tab == 0 -> homeScroll
        tab == 1 -> plansScroll
        tab == 2 -> libraryScroll
        else -> profileScroll
    }
    BackHandler(enabled = editor || showLogs || showDayPlanner || showManual) { editor = false; showLogs = false; showDayPlanner = false; showManual = false }
    var runTask by remember { mutableStateOf<PrintTask?>(null) }
    var deleteTask by remember { mutableStateOf<PrintTask?>(null) }
    var clearLock by remember { mutableStateOf(false) }
    var showAccessibilityGuide by remember { mutableStateOf(false) }
    var showAccessibilityHelp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableIntStateOf(0) }
    // 状态来自系统，每次回到前台更新，避免授权成功后界面仍显示未授权。
    val serviceConnected by BambuAccessibilityService.connected.collectAsState()
    val nightState by NightWatch.state.collectAsState()
    val nightLaunch by NightWatch.launchRequests.collectAsState()
    LaunchedEffect(nightState.active) {
        val params = activity.window.attributes
        params.screenBrightness = if (nightState.active) .03f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params
        if (nightState.active) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(nightLaunch) {
        val id = nightLaunch ?: return@LaunchedEffect
        NightWatch.consume(id)
        if (NightWatch.activityVisible && (!NightWatch.state.value.active || NightWatch.state.value.taskId == id) && ExecutionStore.activeTaskId(activity) == id && ExecutionStore.stage(activity) == 0) {
            runCatching { activity.startActivity(handyIntent(activity)) }
                .onSuccess {
                    if (NightWatch.state.value.active) NightWatch.mark("已请求打开 Handy，请查看执行结果通知；值守继续保持亮屏")
                    scope.launch { RunReporter.log(activity, id, "INFO", "LAUNCH_APP", "竹竹前台页面已请求打开 Handy，等待实际页面确认") }
                }
                .onFailure { error -> scope.launch { RunReporter.finish(activity, id, false, "NIGHT_WATCH", "前台打开 Handy 失败：${error.message}") } }
        } else scope.launch { RunReporter.finish(activity, id, false, "NIGHT_WATCH", "竹竹前台窗口已不可用，夜间启动停止") }
    }
    var accessibilityTick by remember { mutableIntStateOf(0) }
    // 授权来自系统设置，连接来自服务生命周期；连接恢复中不等于授权被关闭。
    val a11yEnabled = remember(resumeTick, accessibilityTick) { accessibilityReady(activity) }
    val a11y = a11yEnabled
    var reconnectExpired by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { accessibilityTick++ }
        }
        activity.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        onDispose { activity.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(a11yEnabled, serviceConnected, resumeTick) {
        reconnectExpired = false
        if (a11yEnabled && !serviceConnected) { delay(8000); reconnectExpired = true }
    }
    suspend fun awaitAccessibility() {
        check(accessibilityReady(activity)) { "请先在系统设置中开启无障碍服务" }
        repeat(40) {
            if (BambuAccessibilityService.connected.value) return
            delay(200)
        }
        error("授权仍开启，但系统尚未连接服务。请检查电池限制和后台运行设置；若仍未恢复，再到系统无障碍设置重连")
    }
    val alarm = remember(resumeTick) { alarmReady(activity) }
    val notifications = remember(resumeTick, permissionTick) { notificationReady(activity) }
    LaunchedEffect(landing) { tab = landing }
    LaunchedEffect(requestedLogs) { if (requestedLogs > 0) { showManual = false; showLogs = true; showDayPlanner = false; editor = false } }
    LaunchedEffect(requestedNightWatch) { if (requestedNightWatch > 0) { showManual = false; tab = 3; showDayPlanner = false; showLogs = false; editor = false } }
    LaunchedEffect(requestedPlanner) { if (requestedPlanner > 0) { showManual = false; showDayPlanner = true; showLogs = false; editor = false } }
    LaunchedEffect(importStatus.active) {
        if (importStatus.active) {
            val session = HandyCatalog.started(activity)
            delay((100_000L - (System.currentTimeMillis() - session)).coerceAtLeast(1))
            if (HandyCatalog.active(activity) && HandyCatalog.started(activity) == session) {
                HandyCatalog.finish(activity, "未能完成导入，请确认无障碍服务已开启且 Handy 已登录。")
            }
        }
    }
    LaunchedEffect(resumeTick) {
        if (HandyCatalog.active(activity) && System.currentTimeMillis() - HandyCatalog.started(activity) > 100_000) {
            HandyCatalog.finish(activity, "导入已超时，请重新尝试。")
        }
        if (alarm) withContext(Dispatchers.IO) {
            runCatching { DayPlanStore.load(activity) }.onFailure { Log.e("DayPlan", "读取排程失败", it) }
            DayPlanStore.plans.value.forEach { plan -> runCatching { com.example.bambuautoprint.scheduling.DayPlanReminders(activity).schedule(plan) }.onFailure { Log.e("DayPlan", "恢复排程提醒失败", it) } }
            runCatching { dao.enabledTasks() }.getOrDefault(emptyList()).forEach { task ->
                runCatching { TaskScheduler(activity).schedule(task) }.onFailure { Log.e("TaskRestore", "恢复闹钟失败", it) }
            }
        }
    }
    fun tell(message: String) { scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(message) } }
    fun open(intent: Intent) {
        runCatching { activity.startActivity(intent) }.onFailure { tell("无法打开：${it.message ?: "系统未提供此入口"}") }
    }
    fun edit(task: PrintTask? = null) {
        if (NightWatch.state.value.active) { tell("请先退出夜间值守，再修改计划"); return }
        editing = task; editor = true; showLogs = false; showDayPlanner = false
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionTick++
        tell(if (it) "通知已开启" else "通知未开启，可在“系统通知设置”中授权")
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = activity.contentResolver.openOutputStream(uri) ?: error("无法写入选择的文件")
                    output.bufferedWriter().use { writer -> dao.latestLogs().asReversed().forEach {
                        writer.appendLine("${formatTime(it.createdAtMillis)}  [${it.level}] ${it.stage}  ${it.message}")
                    } }
                }
            }.onSuccess { tell("日志已导出") }.onFailure { tell("导出失败：${it.message}") }
        } else tell("已取消导出")
    }
    fun startImport(kind: String, source: String, printer: String = "") {
        scope.launch {
        runCatching {
            check(a11y) { "请先在“我的”中开启无障碍服务" }
            if (!BambuAccessibilityService.connected.value) tell("无障碍已授权，正在等待系统恢复服务连接…")
            awaitAccessibility()
            check(!ExecutionStore.isRunning() && ExecutionStore.activeTaskId(activity) == null) { "有打印任务或中断记录，请先处理后再导入" }
            check(!activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请先解锁手机" }
            val launch = handyIntent(activity)
            ExecutionStore.setCalibration(activity, false)
            ExecutionStore.endPrinterCapture(activity)
            HandyCatalog.begin(activity, kind, source, printer)
            activity.startActivity(launch)
        }.onFailure { error ->
            if (HandyCatalog.active(activity)) HandyCatalog.finish(activity, "导入未启动：${error.message}")
            tell(error.message ?: "无法启动导入")
        }
        }
    }
    Scaffold(
        containerColor = Cream,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editor || showLogs || showDayPlanner || showManual) TextButton(onClick = { editor = false; showLogs = false; showDayPlanner = false; showManual = false }) { Text("‹ 返回") }
                Column(Modifier.weight(1f)) {
                    Text(if (showManual) "使用说明书" else if (showDayPlanner) "多任务群发计划" else if (editor) if (editing == null || editing?.id == 0L) "创建打印计划" else "编辑打印计划" else if (showLogs) "执行日志" else listOf("竹竹打印", "我的计划", "资源小仓库", "我的工具箱")[tab], style = MaterialTheme.typography.headlineMedium)
                    if (!editor && !showLogs && !showDayPlanner && !showManual) Text(listOf("让灵感准时成形", "把时间交给竹竹", "从 Handy 导入 · 点选使用", "把准备工作做得明明白白")[tab], color = Mint, style = MaterialTheme.typography.bodySmall)
                }
                if (!editor && !showLogs && !showManual && !showDayPlanner) Surface(color = SoftMint, shape = RoundedCornerShape(18.dp)) { Text("🐼", Modifier.padding(12.dp)) }
            }
        },
        bottomBar = {
            if (!editor && !showLogs && !showDayPlanner && !showManual) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                listOf("首页" to "⌂", "计划" to "◷", "资源库" to "▧", "我的" to "☺").forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { BottomNavIcon(index) }, label = { Text(item.first) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = SoftMint, selectedIconColor = Mint, selectedTextColor = Mint))
                }
            }
        },
        floatingActionButton = {
            if (!editor && !showLogs && !showDayPlanner && !showManual && tab in 0..1) ExtendedFloatingActionButton(onClick = { edit() }, containerColor = Mint, contentColor = Color.White, icon = { Text("+") }, text = { Text("新建计划") })
        }
    ) { padding ->
        key(pageKey) {
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(pageScroll).padding(horizontal = 20.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                showManual -> ManualScreen(activity)
                showDayPlanner -> DayPlanScreen(activity, tasks, ::tell)
                editor -> TaskEditor(editing, resources, busy, onLibrary = { draft -> editing = draft; editor = false; tab = 2 }, onSave = { task ->
                    scope.launch {
                        busy = true
                        try {
                            require(task.printerIdentifier.isNotBlank()) { "请选择或填写打印机" }
                            require(task.modelName.isNotBlank()) { "请选择或填写模型" }
                            require(!task.adaptiveSpeedEnabled || LayerSpeedRules.valid(task.slowFromLayer, task.beforeSpeedMode, task.afterSpeedMode, task.speedTimeoutMinutes)) { "减速层需为正整数，监控限时为 1—1440 分钟，后段速度必须低于前段" }
                            require(TaskScheduler.nextOccurrence(task) != null) { "一次性计划时间已过，请选择未来时间" }
                            if (task.enabled) check(alarmReady(activity)) { "请先开启精确闹钟权限，或关闭“启用计划”后保存" }
                            withContext(Dispatchers.IO) {
                                val old = if (task.id > 0) dao.taskById(task.id) else null
                                val id = if (task.id == 0L) dao.insertTask(task) else { dao.updateTask(task); task.id }
                                try {
                                    if (task.enabled) TaskScheduler(activity).schedule(task.copy(id = id)) else TaskScheduler(activity).cancel(id)
                                } catch (error: Exception) {
                                    if (old == null) dao.deleteTaskById(id) else dao.updateTask(old)
                                    runCatching { if (old == null) TaskScheduler(activity).cancel(id) else TaskScheduler(activity).schedule(old) }
                                    throw error
                                }
                            }
                            editor = false; tab = 1; tell("计划已保存 · ${repeatName(task.repeatMode)}")
                        } catch (error: Exception) { tell("保存失败：${error.message}") } finally { busy = false }
                    }
                })
                showLogs -> {
                    Button(onClick = { exportLauncher.launch("竹竹打印日志.txt") }, modifier = Modifier.fillMaxWidth()) { Text("导出日志") }
                    if (logs.isEmpty()) EmptyCard("还没有执行记录", "创建计划后，可以先做 10 秒闹钟测试。")
                    logs.forEach { log -> SoftCard(color = if (log.level == "ERROR") Peach else Color.White) {
                        Text("${formatTime(log.createdAtMillis)} · ${log.level}", style = MaterialTheme.typography.labelMedium)
                        Text(log.message)
                        Text("任务 #${log.taskId} · ${log.stage}", style = MaterialTheme.typography.bodySmall, color = Mint)
                    } }
                }
                tab == 0 -> {
                    TaskRecoveryCard(activity, allTasks, logs, { clearLock = true }, { showLogs = true }, { showDayPlanner = true })
                    SoftCard(color = SoftMint) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("今天也要\n打印点快乐", style = MaterialTheme.typography.headlineMedium)
                                Spacer(Modifier.height(10.dp))
                                Text("熊猫值班中，\n你的计划已收好。", color = Mint)
                            }
                            PandaPrinter(Modifier.width(155.dp).height(140.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { tab = 1 }, label = { Text("${tasks.count { it.enabled }} 个启用计划") })
                            AssistChip(onClick = { tab = 2 }, label = { Text("${resources.size} 项资源") })
                        }
                    }
                    if (!a11y || !alarm) SoftCard(color = Peach) {
                        Text("还有一点准备工作", style = MaterialTheme.typography.titleMedium)
                        Text("开启无障碍与精确闹钟，计划才能按时运行。")
                        TextButton(onClick = { tab = 3 }) { Text("去完成设置 →") }
                    }
                    SectionTitle("下一次出发", "按下次执行时间排列")
                    val next = tasks.filter { it.enabled }.mapNotNull { task -> TaskScheduler.nextOccurrence(task)?.let { task to it } }.minByOrNull { it.second }
                    if (next == null) EmptyCard("还没有待执行的计划", "先导入设备和模型，再创建你的第一份计划。", { tab = 2 }, "去导入资源")
                    else PlanCard(next.first, onEdit = { edit(next.first) }, onToggle = { enabled ->
                        scope.launch { updateEnabled(activity, dao, next.first, enabled, ::tell) }
                    }, onDelete = { deleteTask = next.first }, onRun = { runTask = next.first }, onCountdown = {
                        runCatching { check(notificationReady(activity)) { "请先开启结果通知，才能看到闹钟测试结果" }; TaskScheduler(activity).scheduleCountdownTest(next.first.id) }.onSuccess { tell("10 秒闹钟已安排，只发送通知") }.onFailure { tell(it.message ?: "无法安排测试") }
                    })
                    SoftCard {
                        Text("从 Handy 带回你的作品", style = MaterialTheme.typography.titleMedium)
                        Text("自动浏览资源列表，导入后在这里点选。")
                        OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth()) { Text("打开资源小仓库") }
                    }
                }
                tab == 1 -> {
                    TaskRecoveryCard(activity, allTasks, logs, { clearLock = true }, { showLogs = true }, { showDayPlanner = true })
                    SoftCard(color = SoftMint) {
                        Text("多任务群发计划", style = MaterialTheme.typography.titleLarge)
                        Text("按收菜时间自动排序，或手动调整顺序和间隔。失败重打后顺延，下一项须确认已换板。")
                        Button(onClick = { showDayPlanner = true }, modifier = Modifier.fillMaxWidth()) { Text("管理多任务群发计划") }
                    }
                    SectionTitle("全部计划", "共 ${tasks.size} 个 · 点击编辑可以修改时间和目标")
                    if (tasks.isEmpty()) EmptyCard("计划列表空空的", "点击“新建计划”，安排第一件作品。")
                    SectionTitle("单次与重复计划")
                    tasks.forEach { task -> PlanCard(task, onEdit = { edit(task) }, onToggle = { enabled ->
                        scope.launch { updateEnabled(activity, dao, task, enabled, ::tell) }
                    }, onDelete = { deleteTask = task }, onRun = { runTask = task }, onCountdown = {
                        runCatching { check(notificationReady(activity)) { "请先开启结果通知，才能看到闹钟测试结果" }; TaskScheduler(activity).scheduleCountdownTest(task.id) }.onSuccess { tell("10 秒闹钟已安排，只发送通知") }.onFailure { tell(it.message ?: "无法安排测试") }
                    }) }
                }
                tab == 2 -> {
                if (editing != null) OutlinedButton(onClick = { editor = true }, modifier = Modifier.fillMaxWidth()) { Text("继续编辑刚才的计划 →") }
                LibraryScreen(resources, importStatus, onImport = ::startImport, onCancel = {
                    HandyCatalog.finish(activity, "导入已取消，已读取的资源保留"); tell("导入已取消")
                }, onRemove = { item -> runCatching { HandyCatalog.remove(activity, item) }.onSuccess { tell("已移除资源") }.onFailure { tell("移除失败：${it.message}") } }, onCreate = { item ->
                    val printer = resources.firstOrNull { it.kind == "PRINTER" }?.name.orEmpty()
                    val model = resources.firstOrNull { it.kind == "MODEL" }
                    val base = editing ?: PrintTask(name = "", triggerAtMillis = System.currentTimeMillis() + 3600000, printerIdentifier = printer, modelName = model?.name.orEmpty(), modelSource = sourceRoute(model?.source.orEmpty()))
                    edit(base.copy(printerIdentifier = if (item.kind == "PRINTER") item.name else base.printerIdentifier,
                        modelName = if (item.kind == "MODEL") item.name else base.modelName,
                        modelSource = if (item.kind == "MODEL") sourceRoute(item.source) else base.modelSource))
                })
                }
                else -> {
                    SoftCard(color = SoftMint) {
                        Text("你好，我是竹竹 🐼", style = MaterialTheme.typography.titleLarge)
                        Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodyMedium)
                        Text("v1.1.2 · 手机信息已自动识别", color = Mint, style = MaterialTheme.typography.bodySmall)
                    }
                    SectionTitle("运行准备", "从系统设置返回后，状态会自动刷新")
                    PermissionCard("无障碍服务", when {
                        !a11yEnabled -> "系统授权未开启，请在系统服务列表打开开关"
                        serviceConnected -> "已授权，服务已连接，可读取 Handy 和执行计划"
                        !reconnectExpired -> "已授权，正在等待系统恢复服务连接…"
                        else -> "授权仍开启，服务未连接。请允许后台运行、关闭电池限制；仍未恢复时再到系统设置重连"
                    }, a11y, statusText = if (!a11yEnabled) "未授权" else if (serviceConnected) "已授权 · 已连接" else "已授权 · 未连接", helpAction = { showAccessibilityHelp = true }) {
                        showAccessibilityGuide = true
                    }
                    val serviceError = remember(resumeTick) { activity.getSharedPreferences("accessibility_diagnostics", Context.MODE_PRIVATE).getString("last_error", null) }
                    if (serviceError != null) Text("最近一次服务异常：$serviceError", style = MaterialTheme.typography.bodySmall)
                    HandyLaunchPreparationCard(activity, ::tell)
                    PermissionCard("精确闹钟", "让计划在指定时间触发", alarm) {
                        if (Build.VERSION.SDK_INT >= 31 && !alarm) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
                        else tell("精确闹钟已经可用")
                    }
                    PermissionCard("结果通知", "导入、测试与运行结果的提醒", notifications) {
                        if (Build.VERSION.SDK_INT >= 33 && !notifications) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                    }
                    ScreenPolicyCard(activity, ::tell)
                    NightWatchCard(activity, tasks, ::tell, resumeTick)
                    SoftCard {
                        Text("工具与记录", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) { Text("使用说明书") }
                        OutlinedButton(onClick = { runCatching { handyIntent(activity) }.onSuccess(::open).onFailure { tell(it.message.orEmpty()) } }, modifier = Modifier.fillMaxWidth()) { Text("打开 Bambu Handy") }
                        OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth()) { Text("查看执行日志") }
                        OutlinedButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)) }, modifier = Modifier.fillMaxWidth()) { Text("系统通知设置") }
                        OutlinedButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("电池与后台运行设置") }
                        TextButton(onClick = {
                            if (ExecutionStore.activeTaskId(activity) == null) tell("没有中断的任务") else clearLock = true
                        }) { Text("处理上次中断的任务") }
                        OutlinedButton(onClick = {
                            if (ExecutionStore.activeTaskId(activity) != null && ExecutionStore.stage(activity) >= 10 && ExecutionStore.isRunning()) {
                                ExecutionStore.requestSpeedStop(activity); tell("已请求停止变速监控；打印继续使用当前档位")
                            } else tell("当前没有正在运行的按层变速监控")
                        }, modifier = Modifier.fillMaxWidth()) { Text("停止按层变速监控") }
                    }
                    Text("导入只保存 Handy 页面可读的名称。资源库里的候选项请先确认；没有读取到的设备或模型仍可手动填写。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    } // pageKey：页面切换时隔离滚动布局，保留每页的 ScrollState。
    runTask?.let { task -> AlertDialog(onDismissRequest = { runTask = null }, title = { Text("现在打印这件作品？") },
        text = { Text("模型：${task.modelName}\n打印机：${task.printerIdentifier}\n确认后将打开 Handy 并尝试提交真实打印。") },
        confirmButton = { TextButton(onClick = {
            runTask = null
            scope.launch {
            runCatching {
                check(!NightWatch.state.value.active) { "请先退出夜间值守，再立即打印" }
                check(a11y) { "请先开启无障碍服务" }
                if (!BambuAccessibilityService.connected.value) tell("无障碍已授权，正在等待系统恢复服务连接…")
                awaitAccessibility()
                check(!HandyCatalog.active(activity)) { "请先完成或取消资源导入" }
                check(!activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请解锁手机" }
                check(ExecutionStore.activeTaskId(activity) == null) { "有运行中或中断的任务，请先检查" }
                val launch = handyIntent(activity)
                ContextCompat.startForegroundService(activity, Intent(activity, AutomationForegroundService::class.java).setAction(AutomationForegroundService.ACTION_EXECUTE)
                    .putExtra(AutomationForegroundService.EXTRA_TASK_ID, task.id).putExtra(AutomationForegroundService.EXTRA_TEST_RUN, true))
                activity.startActivity(launch)
                Toast.makeText(activity, "已启动，请查看 Handy 和执行日志", Toast.LENGTH_LONG).show()
            }.onFailure { tell("启动失败：${it.message}") }
            }
        }) { Text("确认开始") } }, dismissButton = { TextButton(onClick = { runTask = null }) { Text("取消") } }) }
    deleteTask?.let { task -> AlertDialog(onDismissRequest = { deleteTask = null }, title = { Text("删除这个计划？") }, text = { Text(task.name) },
        confirmButton = { TextButton(onClick = {
            deleteTask = null
            scope.launch { runCatching { check(!NightWatch.state.value.active || NightWatch.state.value.taskId != task.id) { "请先退出夜间值守，再删除选定计划" }; withContext(Dispatchers.IO) { check(ExecutionStore.activeTaskId(activity) != task.id) { "这个任务正在运行或存在中断记录，请先处理后再删除" }; TaskScheduler(activity).cancel(task.id); dao.deleteTaskById(task.id) } }
                .onSuccess { tell("计划已删除") }.onFailure { tell("删除失败：${it.message}") } }
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteTask = null }) { Text("取消") } }) }
    if (clearLock) AlertDialog(onDismissRequest = { clearLock = false }, title = { Text("确认打印机状态") },
        text = { Text("请先确认上次任务没有正在打印或排队，再清理中断记录。") },
        confirmButton = { TextButton(onClick = { clearLock = false; tell(if (ExecutionStore.clearInterrupted(activity) != null) "中断记录已清理" else "任务仍在运行，无法清理") }) { Text("已确认，清理") } },
        dismissButton = { TextButton(onClick = { clearLock = false }) { Text("取消") } })
    if (showAccessibilityHelp) AlertDialog(
        onDismissRequest = { showAccessibilityHelp = false },
        title = { Text("无障碍自动关闭？", maxLines = 1, style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("如果退出应用后无障碍自动关闭，请检查手机的启动管理设置：")
            Text("① 打开系统设置，搜索“启动管理”或“应用启动管理”\n② 找到竹竹（本应用），关闭自动管理，改为手动管理\n③ 打开“允许自启动”\n④ 打开“允许关联启动”\n⑤ 打开“允许后台活动”，并保存")
            Text("设置完成后，重新开启一次无障碍服务，再返回竹竹。不同品牌的菜单名称可能不同，请以手机实际设置为准。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { showAccessibilityHelp = false }) { Text("知道了") } },
        dismissButton = { TextButton(onClick = { open(Intent(Settings.ACTION_SETTINGS)) }) { Text("打开系统设置") } }
    )
    if (showAccessibilityGuide) AlertDialog(onDismissRequest = { showAccessibilityGuide = false }, title = { Text("开启打印助手权限") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Android 要求由你在系统页面开启服务。这项权限用于读取 Handy 可见的设备与模型列表，并执行你的打印计划。")
            Text("① 点击“去开启”\n② 找到“拓竹打印流程自动化”（通常在已安装服务或下载的应用里）\n③ 打开开关并确认授权\n④ 返回竹竹，状态会自动更新")
            if (Build.VERSION.SDK_INT >= 33) {
                Text("若显示“受限设置”：打开本应用信息，在右上角菜单中允许受限设置，然后再回来开启服务。不同手机的入口可能有所不同。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }) { Text("打开本应用信息") }
            }
        } },
        confirmButton = { TextButton(onClick = {
            showAccessibilityGuide = false
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("去开启") } }, dismissButton = { TextButton(onClick = { showAccessibilityGuide = false }) { Text("稍后") } })
}

private suspend fun updateEnabled(context: Context, dao: PrintTaskDao, task: PrintTask, enabled: Boolean, tell: (String) -> Unit) {
    try {
        check(DayPlanStore.plans.value.none { p -> p.slots.any { it.job.templateId == task.id } }) { "该任务已用于多任务计划，请在组内管理时间" }
        check(!NightWatch.state.value.active || NightWatch.state.value.taskId != task.id) { "请先退出夜间值守，再修改选定计划" }
        if (enabled) {
            check(alarmReady(context)) { "请先开启精确闹钟权限" }
            require(TaskScheduler.nextOccurrence(task) != null) { "计划时间已过，请编辑时间" }
        }
        withContext(Dispatchers.IO) {
            val updated = task.copy(enabled = enabled)
            if (enabled) TaskScheduler(context).schedule(updated) else TaskScheduler(context).cancel(task.id)
            try { dao.updateTask(updated) } catch (error: Exception) { runCatching { TaskScheduler(context).schedule(task) }; throw error }
        }
        tell(if (enabled) "计划已启用" else "计划已暂停")
    } catch (error: Exception) { tell("设置失败：${error.message}") }
}

@Composable private fun EmptyCard(title: String, subtitle: String, action: (() -> Unit)? = null, label: String = "") {
    SoftCard { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(label) } } }
}
@Composable private fun PermissionCard(title: String, description: String, ready: Boolean, statusText: String = if (ready) "已就绪" else "需要设置", helpAction: (() -> Unit)? = null, action: () -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareStatusIcon(ready)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(statusText, style = MaterialTheme.typography.bodySmall, color = Mint) }
            TextButton(onClick = action) { Text(if (ready) "查看" else "开启") }
        }
        Text(description, style = MaterialTheme.typography.bodySmall)
        // 帮助入口位于同一卡片底部，仅无障碍卡片传入此操作。
        helpAction?.let { onHelp ->
            TextButton(onClick = onHelp, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text("无障碍自动关闭？", style = MaterialTheme.typography.bodySmall, color = Mint)
            }
        }
    }
}
@Composable private fun PlanCard(task: PrintTask, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onRun: () -> Unit, onCountdown: () -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(task.name.ifBlank { "我的打印计划" }, style = MaterialTheme.typography.titleMedium); Text(repeatName(task.repeatMode), color = Mint, style = MaterialTheme.typography.labelMedium) }
            Switch(task.enabled, onCheckedChange = onToggle)
        }
        Text(TaskScheduler.nextOccurrence(task)?.let(::formatTime) ?: "时间已过，请编辑", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(task.modelName, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("▧ ${task.printerIdentifier}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (task.adaptiveSpeedEnabled) Text("第 ${task.slowFromLayer} 层之前${task.beforeSpeedMode} · 从本层起${task.afterSpeedMode}", color = Mint, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("编辑") }
            Button(onClick = onRun, modifier = Modifier.weight(1f)) { Text("现在打印") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCountdown) { Text("10 秒闹钟测试") }
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun sourceRoute(source: String) = when (source) {
    "打印历史" -> "我的>打印历史"; "设备文件" -> "设备>打印机设置>文件管理>模型"; else -> "我的>收藏 & 点赞>收藏列表>收藏模型"
}

@Composable private fun LibraryScreen(resources: List<HandyResource>, status: ImportStatus, onImport: (String, String, String) -> Unit,
    onCancel: () -> Unit, onRemove: (HandyResource) -> Unit, onCreate: (HandyResource) -> Unit) {
    var kind by remember { mutableStateOf("PRINTER") }
    var source by remember { mutableStateOf("收藏") }
    var printer by remember { mutableStateOf(resources.firstOrNull { it.kind == "PRINTER" }?.name.orEmpty()) }
    SoftCard(color = Peach) {
        Text("把资源带回小仓库", style = MaterialTheme.typography.titleLarge)
        Text("竹竹会打开 Handy、浏览列表并导入可读名称。你只需在导入后确认和选择。")
        Button(onClick = { onImport("PRINTER", "设备", "") }, enabled = !status.active, modifier = Modifier.fillMaxWidth()) { Text("导入我的打印机") }
        ChoiceField("模型来源", source, listOf("收藏", "打印历史", "设备文件")) { source = it }
        if (source == "设备文件") ChoiceField("要读取的打印机", printer, resources.filter { it.kind == "PRINTER" }.map { it.name }) { printer = it }
        OutlinedButton(onClick = { onImport("MODEL", source, printer) }, enabled = !status.active && (source != "设备文件" || printer.isNotBlank()), modifier = Modifier.fillMaxWidth()) { Text("导入模型列表") }
        if (status.active) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        Text(status.message, style = MaterialTheme.typography.bodySmall)
        if (status.active) TextButton(onClick = onCancel) { Text("取消导入") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(kind == "PRINTER", { kind = "PRINTER" }, label = { Text("打印机 ${resources.count { it.kind == "PRINTER" }}") })
        FilterChip(kind == "MODEL", { kind = "MODEL" }, label = { Text("模型 ${resources.count { it.kind == "MODEL" }}") })
    }
    val items = resources.filter { it.kind == kind }
    if (items.isEmpty()) EmptyCard("这里还没有${if (kind == "PRINTER") "打印机" else "模型"}", "使用上方导入按钮。读取不到时可以在新建计划里手动填写。")
    items.forEach { item -> SoftCard {
        Text(if (item.kind == "PRINTER") "▧  ${item.name}" else "◇  ${item.name}", style = MaterialTheme.typography.titleMedium)
        Text("来自 Handy · ${item.source}", color = Mint, style = MaterialTheme.typography.bodySmall)
        if (item.detail.isNotBlank()) Text(item.detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onCreate(item) }, modifier = Modifier.weight(1f)) { Text("选用 · 创建计划") }
            TextButton(onClick = { onRemove(item) }) { Text("移除") }
        }
    } }
}

@Composable private fun ChoiceField(label: String, value: String, choices: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mint)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value.ifBlank { if (choices.isEmpty()) "暂无导入项" else "请选择" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("⌄")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                if (choices.isEmpty()) DropdownMenuItem(text = { Text("请先去资源库导入") }, onClick = { expanded = false }, enabled = false)
                choices.distinct().forEach { option -> DropdownMenuItem(text = { Text(option.ifBlank { "沿用 Handy" }) }, onClick = { onPick(option); expanded = false }) }
            }
        }
    }
}

@Composable private fun TaskEditor(task: PrintTask?, resources: List<HandyResource>, busy: Boolean, onLibrary: (PrintTask) -> Unit, onSave: (PrintTask) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initial = remember(task) { task ?: PrintTask(name = "", triggerAtMillis = System.currentTimeMillis() + 3600000, printerIdentifier = "", modelName = "") }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var dateTime by remember(initial) { mutableStateOf(Instant.ofEpochMilli(initial.triggerAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()) }
    var repeat by remember(initial) { mutableStateOf(initial.repeatMode) }
    var mask by remember(initial) { mutableIntStateOf(if (initial.weekdaysMask == 0) 1 shl (dateTime.dayOfWeek.value - 1) else initial.weekdaysMask) }
    var printer by remember(initial) { mutableStateOf(initial.printerIdentifier) }
    var model by remember(initial) { mutableStateOf(initial.modelName) }
    var source by remember(initial) { mutableStateOf(initial.modelSource) }
    var ams by remember(initial) { mutableStateOf(initial.useAms) }
    var slot by remember(initial) { mutableStateOf(initial.amsSlot.ifBlank { "1" }) }
    var material by remember(initial) { mutableStateOf(initial.materialType) }
    var bed by remember(initial) { mutableStateOf(initial.bedType) }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var advanced by remember { mutableStateOf(false) }
    var profile by remember(initial) { mutableStateOf(initial.printProfile) }
    var retries by remember(initial) { mutableStateOf(initial.retryCount.toString()) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutMinutes.toString()) }
    var adaptive by remember(initial) { mutableStateOf(initial.adaptiveSpeedEnabled) }
    var slowLayer by remember(initial) { mutableStateOf(initial.slowFromLayer.toString()) }
    var beforeSpeed by remember(initial) { mutableStateOf(initial.beforeSpeedMode) }
    var afterSpeed by remember(initial) { mutableStateOf(initial.afterSpeedMode) }
    var speedTimeout by remember(initial) { mutableStateOf(initial.speedTimeoutMinutes.toString()) }
    fun draft() = initial.copy(name = name.trim().ifBlank { model.ifBlank { "我的打印计划" } }, triggerAtMillis = dateTime.withSecond(0).withNano(0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        repeatMode = repeat, weekdaysMask = mask, printerIdentifier = printer.trim(), modelName = model.trim(), modelSource = if (source.startsWith("设备>")) "设备>${printer.trim()}>打印机设置>文件管理>模型" else source.trim(),
        printProfile = profile.trim(), bedType = bed, materialType = material, useAms = ams, amsSlot = if (ams) slot else "", enabled = enabled, requireCalibration = false,
        retryCount = retries.toIntOrNull()?.coerceIn(0, 5) ?: 1, timeoutMinutes = timeout.toIntOrNull()?.coerceIn(1, 240) ?: 20,
        adaptiveSpeedEnabled = adaptive, slowFromLayer = slowLayer.toIntOrNull() ?: 0, beforeSpeedMode = beforeSpeed, afterSpeedMode = afterSpeed,
        speedTimeoutMinutes = speedTimeout.toIntOrNull() ?: 0)
    SoftCard(color = SoftMint) { Text("一份计划，一件好作品", style = MaterialTheme.typography.titleLarge); Text("选目标、定时间，竹竹来记住。") }
    SoftCard {
        SectionTitle("01  给计划起个名字")
        Input("例如：早上打印小花盆", name) { name = it }
    }
    SoftCard {
        SectionTitle("02  什么时候开始")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, y, m, d -> dateTime = LocalDate.of(y, m + 1, d).atTime(dateTime.toLocalTime()) },
                    dateTime.year, dateTime.monthValue - 1, dateTime.dayOfMonth).show()
            }, modifier = Modifier.weight(1f)) { Text(dateTime.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))) }
            Button(onClick = { TimePickerDialog(context, { _, h, m -> dateTime = dateTime.withHour(h).withMinute(m).withSecond(0).withNano(0) }, dateTime.hour, dateTime.minute, true).show() },
                modifier = Modifier.weight(1f)) { Text(dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))) }
        }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ONCE", "DAILY", "WEEKLY", "CUSTOM").forEach { mode -> FilterChip(repeat == mode, { repeat = mode }, label = { Text(repeatName(mode), style = MaterialTheme.typography.labelSmall) }) }
        }
        if (repeat == "WEEKLY" || repeat == "CUSTOM") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, day ->
                    val selected = mask and (1 shl i) != 0
                    Text(day, Modifier.weight(1f).background(if (selected) Mint else SoftMint, RoundedCornerShape(12.dp)).clickable { mask = mask xor (1 shl i) }.padding(vertical = 10.dp), textAlign = TextAlign.Center, color = if (selected) Color.White else Mint)
                }
            }
        }
    }
    SoftCard {
        SectionTitle("03  选择设备和作品")
        ChoiceField("已导入的打印机", printer, resources.filter { it.kind == "PRINTER" }.map { it.name }) { printer = it }
        Input("打印机名称（可手动填写）", printer) { printer = it }
        ChoiceField("已导入的模型", model, resources.filter { it.kind == "MODEL" }.map { "${it.name} · ${it.source}" }) { picked ->
            resources.firstOrNull { it.kind == "MODEL" && "${it.name} · ${it.source}" == picked }?.let { selected ->
                model = selected.name; source = sourceRoute(selected.source)
            }
        }
        Input("模型名称（可手动填写）", model) { model = it }
        TextButton(onClick = { onLibrary(draft()) }) { Text("去资源库导入设备和模型 →") }
    }
    SoftCard {
        SectionTitle("04  打印选项")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("使用 AMS", Modifier.weight(1f)); Switch(ams, { ams = it }) }
        if (ams) ChoiceField("AMS 槽位", slot, listOf("1", "2", "3", "4")) { slot = it }
        ChoiceField("材料", material, listOf("PLA", "PETG", "ABS", "ASA", "TPU")) { material = it }
        ChoiceField("打印板（不指定时沿用 Handy）", bed, listOf("", "纹理PE打印板", "纹理 PEI 打印板", "光面 PEI 打印板")) { bed = it }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("启用计划", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级选项 ↑" else "高级选项 ↓") }
        if (advanced) {
            Input("模型入口路径", source) { source = it }
            Input("打印配置名称", profile) { profile = it }
            Input("失败重试次数（0—5）", retries) { retries = it }
            Input("超时分钟（1—240）", timeout) { timeout = it }
        }
    }
    SoftCard(color = SoftMint) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("05  按层变速", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Switch(adaptive, { adaptive = it }) }
        Text("让细高零件在高层时慢下来。默认关闭，可以按作品单独设置。", style = MaterialTheme.typography.bodySmall)
        if (adaptive) {
            Input("从第几层起减速（包含本层）", slowLayer) { slowLayer = it }
            ChoiceField("前段速度", beforeSpeed, listOf("标准", "运动", "狂暴")) { beforeSpeed = it }
            ChoiceField("后段速度", afterSpeed, listOf("静音", "标准", "运动")) { afterSpeed = it }
            Input("监控限时（分钟，1—1440）", speedTimeout) { speedTimeout = it }
            Text("示例：151 表示前 150 层用前段档位，第 151 层起用后段档位。需要保持手机解锁、Handy 设备页前台；约每 2 秒读取一次，实际切换可能稍有延迟。", style = MaterialTheme.typography.bodySmall)
            Text("减速不能保证避免炒面。识别不到打印机、模型或层数时会通知检查，打印继续使用原档位。", style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(enabled = !busy && (repeat !in setOf("WEEKLY", "CUSTOM") || mask != 0), onClick = {
        onSave(draft())
    }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (busy) "正在保存…" else "保存打印计划") }
    if (repeat in setOf("WEEKLY", "CUSTOM") && mask == 0) Text("至少选择一个星期，才能保存。", color = MaterialTheme.colorScheme.error)
}

@Composable private fun Input(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
}

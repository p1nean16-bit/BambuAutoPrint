package com.example.bambuautoprint.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import com.example.bambuautoprint.data.AppGraph
import com.example.bambuautoprint.data.PrintTask
import com.example.bambuautoprint.data.HandyCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 按用户录入的模型、打印机、耗材槽和录屏中出现的中文控件推进流程。
 * 任何未识别的对话框都不做通用“确定”，超时后记录失败，避免误点其他操作。
 */
class BambuAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        recordServiceError("后台处理", error)
    })
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadedTaskId = -1L
    private var loadedStartedAt = 0L
    private var loadedTask: PrintTask? = null
    private var lastActionAt = 0L
    private var retryCount = 0
    private var materialDialogSeenAt = 0L
    private var materialSelectorRequestedAt = 0L
    private var modelScrolls = 0
    private var finishing = false
    private var timeoutRunnable: Runnable? = null
    private val importer by lazy { HandyImporter(this) }
    private val speedController by lazy { LayerSpeedController(this,
        log = { message -> loadedTask?.let { task -> scope.launch { RunReporter.log(this@BambuAccessibilityService, task.id, "INFO", "LAYER_SPEED", message) } } },
        finish = { success, message -> loadedTask?.let { task -> scope.launch { RunReporter.finish(this@BambuAccessibilityService, task.id, success, "LAYER_SPEED", message) } } }
    ) }
    // 有些 Handy 页面点击后不再发事件；轮询兜底，避免按钮已点击却流程永久停住。
    private val pulse = object : Runnable {
        override fun run() {
            try {
            NightWatch.checkScreen()
            if (HandyCatalog.active(this@BambuAccessibilityService)) {
                importer.onPageChanged()
            } else if (ExecutionStore.activeTaskId(this@BambuAccessibilityService) != null) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                try {
                    val root = rootInActiveWindow
                    event.packageName = root?.packageName
                    root?.recycle()
                    onAccessibilityEvent(event)
                } finally { event.recycle() }
            }
            } catch (error: Exception) { recordServiceError("页面轮询", error) }
            finally { mainHandler.postDelayed(this, 1200) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { HandyCatalog.load(this) }.onFailure { recordServiceError("连接初始化", it) }
        launchService = this
        connectionState.value = true
        NightWatch.attach(this)
        mainHandler.removeCallbacks(pulse)
        mainHandler.post(pulse)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 控件树在窗口关闭时可能失效；异常不能逃出系统回调导致服务进程崩溃。
        try { handleEvent(event) }
        catch (error: Exception) { recordServiceError("无障碍事件", error) }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (ExecutionStore.calibration(this) && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            CalibrationRecorder.capture(this, event)
        }
        if (event.packageName?.toString() != AutomationForegroundService.BAMBU_PACKAGE) return
        ExecutionStore.setAppForeground(this, true)

        if (HandyCatalog.active(this)) {
            importer.onPageChanged()
            return
        }

        // 设备识别仅在用户主动开启采集后运行，且只保存可点击控件的可见名称。
        if (ExecutionStore.printerCaptureActive(this)) {
            val captureRoot = rootInActiveWindow ?: return
            try {
                ExecutionStore.savePrinterCandidates(this, collectClickableLabels(captureRoot))
            } finally {
                captureRoot.recycle()
            }
            return
        }

        val taskId = ExecutionStore.activeTaskId(this) ?: return
        // 进程重启留下的记录需用户核查；只有现存执行器允许恢复监控。
        if (!ExecutionStore.isRunning()) return
        val startedAt = ExecutionStore.startedAt(this)
        if (taskId != loadedTaskId || startedAt != loadedStartedAt) {
            loadedTaskId = taskId
            loadedStartedAt = startedAt
            finishing = false
            modelScrolls = 0
            materialSelectorRequestedAt = 0L
            materialDialogSeenAt = 0L
            scope.launch {
                val task = AppGraph.database(this@BambuAccessibilityService).taskDao().taskById(taskId)
                withContext(Dispatchers.Main) {
                    if (ExecutionStore.activeTaskId(this@BambuAccessibilityService) != taskId) return@withContext
                    loadedTask = task
                    if (ExecutionStore.stage(this@BambuAccessibilityService) >= 10) {
                        // 服务重连不能重放提交动作；只恢复已提交任务的只读校验和变速。
                        finishing = true
                        if (task != null && task.adaptiveSpeedEnabled) speedController.start(task)
                        return@withContext
                    }
                    scheduleTimeout(taskId, ExecutionStore.stage(this@BambuAccessibilityService))
                    val currentRoot = rootInActiveWindow
                    if (task != null && currentRoot?.packageName?.toString() == AutomationForegroundService.BAMBU_PACKAGE) {
                        try {
                            process(task, currentRoot)
                        } catch (e: Exception) {
                            Log.e(TAG, "首次处理 Bambu Handy 页面失败", e)
                            scope.launch {
                                RunReporter.log(this@BambuAccessibilityService, task.id, "ERROR", "ACCESSIBILITY", e.message ?: "页面处理异常")
                            }
                        }
                    }
                    currentRoot?.recycle()
                }
            }
            return
        }
        if (finishing) return
        val task = loadedTask ?: return
        val root = rootInActiveWindow ?: return
        try {
            process(task, root)
        } catch (e: Exception) {
            Log.e(TAG, "处理 Bambu Handy 页面失败", e)
            scope.launch { RunReporter.log(this@BambuAccessibilityService, task.id, "ERROR", "ACCESSIBILITY", e.message ?: "控件树读取失败") }
        } finally {
            root.recycle()
        }
    }

    private fun process(task: PrintTask, root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - ExecutionStore.startedAt(this) > task.timeoutMinutes.coerceAtLeast(1) * 60_000L) {
            finish(task, false, "TIMEOUT", "执行超过 ${task.timeoutMinutes} 分钟")
            return
        }
        if (now - lastActionAt < 900L) return

        val texts = collectTexts(root).joinToString(" | ")
        if (texts.contains("立即登录") || texts.contains("登录/注册")) {
            finish(task, false, "LOGIN_REQUIRED", "Bambu Handy 当前未登录，请先在 App 中登录后重试")
            return
        }
        val stage = ExecutionStore.stage(this)
        when (stage) {
            0 -> {
                if (ExecutionStore.sourceStep(this) >= HandyNavigation.route(task.modelSource, task.printerIdentifier).size && clickText(root, task.modelName)) {
                    advance(task, 1, "找到并打开目标模型：${task.modelName}")
                } else {
                    val route = HandyNavigation.route(task.modelSource, task.printerIdentifier)
                    val routeIndex = ExecutionStore.sourceStep(this)
                    if (routeIndex < route.size && clickSourceEntry(root, route[routeIndex])) {
                        ExecutionStore.setSourceStep(this, routeIndex + 1)
                        lastActionAt = now
                        record(task, "打开模型入口步骤：${route[routeIndex]}")
                    } else if (routeIndex >= route.size && modelScrolls < 12) {
                        val list = findFirst(root) { it.isScrollable && it.isVisibleToUser }
                        if (list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
                            modelScrolls++
                            lastActionAt = now
                            record(task, "查找模型，翻页 $modelScrolls")
                        }
                    }
                }
            }
            1 -> if (texts.contains(task.modelName, true) && (clickText(root, task.preparePrintLabel) || clickText(root, "再次打印"))) {
                advance(task, 2, "模型详情匹配成功，已点击准备打印")
            }
            2 -> {
                if (findText(root, task.printCurrentPlateLabel) != null) {
                    advance(task, 6, "已进入打印准备页，无需新手引导")
                    return
                }
                if (texts.contains("选择打印配置") || findText(root, task.nextStepLabel) != null) {
                    val profile = task.printProfile.trim()
                    if (profile.isNotEmpty() && !texts.contains(profile, true)) return
                    if (clickText(root, task.nextStepLabel)) advance(task, 3, "确认打印配置并进入打印准备页")
                }
            }
            3 -> if (findText(root, task.printCurrentPlateLabel) != null) {
                advance(task, 6, "当前没有新手提示，进入打印准备")
            } else if (texts.contains("1/3") && texts.contains("打印板型号") && clickText(root, task.tutorialConfirmLabel)) {
                advance(task, 4, "已确认打印板提示")
            }
            4 -> if (findText(root, task.printCurrentPlateLabel) != null) {
                advance(task, 6, "新手提示已关闭，进入打印准备")
            } else if (texts.contains("2/3") && (texts.contains("AMS", true) || texts.contains("耗材")) && clickText(root, task.tutorialConfirmLabel)) {
                advance(task, 5, "已确认 AMS/耗材提示")
            }
            5 -> if (findText(root, task.printCurrentPlateLabel) != null) {
                advance(task, 6, "新手提示已关闭，进入打印准备")
            } else if (texts.contains("3/3") && clickText(root, task.queueDismissLabel)) {
                advance(task, 6, "已关闭打印队列说明")
            }
            6 -> processPrintPreparation(task, root, texts)
            7 -> processPrintConfirmation(task, root, texts)
            8 -> if (texts.contains("发送打印任务")) {
                advance(task, 9, "Bambu Handy 正在发送打印任务")
            } else if (texts.contains("忙碌") || texts.contains("打印中")) {
                finish(task, true, "SUBMITTED", "Bambu Handy 已接收打印任务")
            }
            9 -> if (texts.contains("忙碌") || texts.contains("打印中")) {
                finish(task, true, "SUBMITTED", "Bambu Handy 已接收打印任务")
            }
        }
    }

    private fun processPrintPreparation(task: PrintTask, root: AccessibilityNodeInfo, texts: String) {
        // 耗材弹窗可能遮住打印机/打印板文字：先处理明确识别的弹窗，回到准备页后再校验设备。
        if (texts.contains("选择打印耗材")) {
            materialSelectorRequestedAt = 0L
            if (materialDialogSeenAt == 0L) {
                materialDialogSeenAt = System.currentTimeMillis()
                val target = if (task.useAms) "AMS ${task.amsSlot}" else "Ext ${task.materialType}"
                record(task, "已识别耗材弹窗，目标：$target；必须主动按任务槽位选择，再点击确定；截图中的手动选中状态不作为判断依据")
            }
            if (System.currentTimeMillis() - materialDialogSeenAt > 20_000L) {
                val labels = collectTexts(root).filter { it.trim() in setOf("1", "2", "3", "4", "Ext", "External", "AMS lite", "PLA", "PETG", "确定") }.distinct().joinToString("、")
                finish(task, false, "MATERIAL_SELECTOR", "已看到耗材弹窗，但未完成选择/确认；配置 AMS=${task.useAms}，槽位=${task.amsSlot}，材料=${task.materialType}；可读控件：$labels。请核对槽位配置；图形槽位可能未向无障碍暴露可点击文字")
                return
            }
            val targetSpool = if (task.useAms) task.amsSlot else "Ext"
            if ((task.useAms && task.amsSlot.isBlank()) || (!task.useAms && task.materialType.isBlank())) {
                finish(task, false, "MATERIAL_MAPPING", "请填写 AMS 槽位或外置料盘材料类型")
                return
            }
            if (!ExecutionStore.slotChosen(this)) {
                if (clickText(root, targetSpool)) {
                    ExecutionStore.setSlotChosen(this, true)
                    lastActionAt = System.currentTimeMillis()
                    record(task, "选择耗材：${if (task.useAms) "AMS 槽位 ${task.amsSlot}" else "外置料盘 ${task.materialType}"}")
                }
                return
            }
            if (clickText(root, task.tutorialConfirmLabel)) {
                lastActionAt = System.currentTimeMillis()
                record(task, "确认耗材映射")
            }
            return
        }
        // ACTION_CLICK 返回成功只代表点击已受理；没有识别到弹窗时不得反复点击或继续提交。
        if (materialSelectorRequestedAt > 0L && !ExecutionStore.slotChosen(this)) {
            if (System.currentTimeMillis() - materialSelectorRequestedAt > 20_000L) {
                finish(task, false, "MATERIAL_SELECTOR", "请求打开耗材选择器后，20 秒内未识别到“选择打印耗材”弹窗；请提供当前 Handy 页面截图，检查是否点击了错误的材料控件或弹窗文案不同")
            }
            return
        }
        materialDialogSeenAt = 0L
        val printerMatches = task.printerIdentifier.isBlank() || texts.contains(task.printerIdentifier, ignoreCase = true)
        val bedMatches = task.bedType.isBlank() || texts.contains(task.bedType, ignoreCase = true)

        if (texts.contains("忙碌") || texts.contains("打印中")) {
            finish(task, false, "PRINTER_BUSY", "目标打印机已处于忙碌/打印状态，为防止重复排队已停止")
            return
        }
        if (texts.contains("离线") || texts.contains("未连接")) {
            finish(task, false, "PRINTER_OFFLINE", "Bambu Handy 页面显示打印机离线或未连接")
            return
        }

        if (!printerMatches || !bedMatches) {
            val openedOrSelected = if (!printerMatches) {
                clickText(root, task.printerIdentifier) ||
                    clickText(root, "确认打印机和打印板型号") || clickText(root, "打印板型号")
            } else {
                clickText(root, task.bedType)
            }
            if (openedOrSelected) {
                lastActionAt = System.currentTimeMillis()
                record(task, if (!printerMatches) "打开/选择目标打印机：${task.printerIdentifier}" else "选择打印板：${task.bedType}")
            }
            return
        }

        if (texts.contains("颜色或材料类型的差异会导致耗材不匹配") &&
            ((task.useAms && task.amsSlot.isBlank()) || (!task.useAms && task.materialType.isBlank()))) {
            finish(task, false, "MATERIAL_MAPPING", "当前耗材与打印配置不匹配，请配置正确的耗材类型/AMS 槽位")
            return
        }
        if (texts.contains("匹配我的耗材") && !ExecutionStore.slotChosen(this) &&
            ((task.useAms && task.amsSlot.isNotBlank()) || !task.useAms)) {
            if (clickText(root, task.materialType)) {
                lastActionAt = System.currentTimeMillis()
                materialSelectorRequestedAt = lastActionAt
                record(task, "已请求打开耗材选择器，等待识别弹窗（尚未确认耗材）")
                return
            }
        }
        if (findText(root, task.printCurrentPlateLabel) != null) {
            if (task.useAms && task.amsSlot.isBlank()) {
                finish(task, false, "MATERIAL_MAPPING", "使用 AMS 时请先配置槽位（例如 1、2、3 或 4）")
                return
            }
            if (ExecutionStore.slotChosen(this)) {
                val expected = if (task.useAms) task.amsSlot else task.materialType
                if (collectTexts(root).none { it.trim().equals(expected, true) }) return
            } else {
                // 必须确认过耗材映射后才提交，不能因为当前页面中存在打印按钮就跳过选择。
                return
            }
            if (clickText(root, task.printCurrentPlateLabel)) advance(task, 7, "已点击打印当前盘，等待确认/打印机状态")
        }
    }

    private fun processPrintConfirmation(task: PrintTask, root: AccessibilityNodeInfo, texts: String) {
        val knownConfirmation = texts.contains("开始打印") || texts.contains("确认打印") || texts.contains("发送打印任务")
        if (knownConfirmation && (clickText(root, "开始打印") || clickText(root, task.finalConfirmLabel) ||
                ((texts.contains("开始打印") || texts.contains("确认打印")) && clickText(root, task.tutorialConfirmLabel)))) {
            advance(task, 8, "已确认打印请求，等待打印机接收状态")
            return
        }
        if (texts.contains("忙碌") || texts.contains("打印中")) {
            finish(task, true, "SUBMITTED", "Bambu Handy 已接收打印任务")
        } else if (texts.contains("发送打印任务")) {
            advance(task, 9, "Bambu Handy 正在发送打印任务")
        }
    }

    private fun advance(task: PrintTask, newStage: Int, message: String) {
        lastActionAt = System.currentTimeMillis()
        retryCount = 0
        ExecutionStore.setStage(this, newStage)
        record(task, message)
        scheduleTimeout(task.id, newStage)
    }

    private fun scheduleTimeout(taskId: Long, stage: Int) {
        timeoutRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (ExecutionStore.activeTaskId(this) != taskId || ExecutionStore.stage(this) != stage) return@Runnable
            val task = loadedTask ?: return@Runnable
            if (stage < 7 && retryCount < task.retryCount.coerceAtLeast(0)) {
                retryCount++
                lastActionAt = 0L
                scope.launch { RunReporter.log(this@BambuAccessibilityService, taskId, "WARN", "RETRY", "步骤 $stage 超时，准备重试第 $retryCount 次") }
                rootInActiveWindow?.let { root -> process(task, root); root.recycle() }
                scheduleTimeout(taskId, stage)
            } else {
                    val reason = when (stage) {
                        0 -> "没有在模型入口找到目标模型：${task.modelName}"
                        1 -> "已打开模型入口，但未确认目标模型详情页"
                        2 -> "未找到指定打印配置或“${task.nextStepLabel}”按钮"
                        6 -> "打印机、打印板或耗材映射步骤超时"
                        else -> "步骤 $stage 未在限定时间内完成"
                    }
                    finish(task, false, "STEP_TIMEOUT", "$reason；可运行校准并检查控件文案/坐标")
            }
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, STEP_TIMEOUT_MS)
    }

    /** 入口别名限定在来源导航，不扩大模型名称和 AMS 数字槽位的匹配范围。 */
    private fun clickSourceEntry(root: AccessibilityNodeInfo, label: String): Boolean {
        HandyNavigation.special(this, root, label, loadedTask?.printerIdentifier.orEmpty())?.let { return it }
        val aliases = when (label.trim()) {
            "收藏", "我的收藏", "收藏 & 点赞", "收藏&点赞" -> listOf("收藏 & 点赞", "收藏&点赞", "收藏与点赞", "我的收藏", "收藏", "Favorites")
            else -> listOf(label)
        }
        return aliases.any { clickText(root, it) }
    }

    private fun clickText(root: AccessibilityNodeInfo, label: String): Boolean {
        if (label.isBlank()) return false
        val node = findText(root, label)
        if (node != null) {
            var candidate: AccessibilityNodeInfo? = node
            repeat(5) {
                if (candidate?.isClickable == true && candidate!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                candidate = candidate?.parent
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        // 仅当文字/控件点击不可用时，使用用户校准的归一化坐标；没有配置就不猜位置。
        return clickCalibratedPoint(label)
    }

    private fun clickCalibratedPoint(label: String): Boolean {
        val json = runCatching { JSONObject(loadedTask?.coordinateOverridesJson.orEmpty()) }.getOrNull() ?: return false
        val point = json.optJSONArray(label) ?: return false
        if (point.length() < 2) return false
        val xRatio = point.optDouble(0, -1.0).toFloat()
        val yRatio = point.optDouble(1, -1.0).toFloat()
        if (xRatio !in 0f..1f || yRatio !in 0f..1f) return false
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(xRatio * metrics.widthPixels, yRatio * metrics.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val accepted = dispatchGesture(gesture, null, mainHandler)
        if (accepted) lastActionAt = System.currentTimeMillis()
        return accepted
    }

    private fun findText(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val needle = label.trim().lowercase(Locale.ROOT)
        if (needle.isBlank()) return null
        // 先精确匹配，尤其 AMS 的“1/2/3/4”不能误中时间、进度或其他数字。
        findFirst(root) { node ->
            node.isVisibleToUser && (node.text?.toString()?.trim()?.lowercase(Locale.ROOT) == needle ||
                node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) == needle)
        }?.let { return it }
        if (needle.length < 3) return null
        return findFirst(root) { node ->
            node.isVisibleToUser && listOfNotNull(node.text, node.contentDescription).any {
                val text = it.toString().trim().lowercase(Locale.ROOT)
                text.length <= needle.length + 16 && text.contains(needle)
            }
        }
    }

    private fun findFirst(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirst(child, predicate)
            if (result != null) return result
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo): List<String> {
        val values = mutableListOf<String>()
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
        for (i in 0 until node.childCount) node.getChild(i)?.let { values += collectTexts(it) }
        return values
    }

    /** 只收集自身或位于可点击卡片中的文字，尽量排除页面装饰文字。 */
    private fun collectClickableLabels(node: AccessibilityNodeInfo, clickableParent: Boolean = false): List<String> {
        val insideClickable = clickableParent || node.isClickable
        val values = mutableListOf<String>()
        if (insideClickable) {
            node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> values += collectClickableLabels(child, insideClickable) }
        }
        val ignored = setOf(
            "设备", "首页", "模型", "社区", "我的", "发现", "搜索", "添加设备", "打印机",
            "返回", "关闭", "更多", "切换", "在线", "离线", "空闲", "打印中", "正在打印",
            "home", "device", "devices", "model", "models", "community", "me", "search", "back", "more"
        )
        return values.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 2..48 && it.lowercase(Locale.ROOT) !in ignored }
            .distinct()
            .take(20)
            .toList()
    }

    private fun record(task: PrintTask, message: String) {
        scope.launch { RunReporter.log(this@BambuAccessibilityService, task.id, "INFO", "UI_STEP_${ExecutionStore.stage(this@BambuAccessibilityService)}", message) }
    }

    private fun finish(task: PrintTask, success: Boolean, stage: String, message: String) {
        if (finishing) return
        finishing = true
        timeoutRunnable?.let(mainHandler::removeCallbacks)
        if (success && task.adaptiveSpeedEnabled) {
            runCatching { com.example.bambuautoprint.scheduling.GroupCoordinator.report(this, task.id, true) }.onFailure { recordServiceError("群发状态", it) }
            ExecutionStore.setStage(this, 10)
            speedController.start(task)
            return
        }
        if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    buffer.close()
                    val savedPath = if (bitmap == null) null else runCatching {
                        val directory = File(filesDir, "screenshots").apply { mkdirs() }
                        val file = File(directory, "task_${task.id}_${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        file.absolutePath
                    }.getOrNull()
                    bitmap?.recycle()
                    scope.launch {
                        RunReporter.finish(
                            this@BambuAccessibilityService, task.id, false, stage,
                            if (savedPath == null) "$message（截图保存失败）" else message,
                            savedPath
                        )
                    }
                }

                override fun onFailure(errorCode: Int) {
                    scope.launch {
                        RunReporter.finish(this@BambuAccessibilityService, task.id, false, stage, "$message（截图失败：$errorCode）")
                    }
                }
            })
        } else {
            scope.launch { RunReporter.finish(this@BambuAccessibilityService, task.id, success, stage, message) }
        }
    }

    override fun onInterrupt() = Unit

    private fun recordServiceError(location: String, error: Throwable) {
        Log.e(TAG, "$location 异常", error)
        runCatching { getSharedPreferences("accessibility_diagnostics", MODE_PRIVATE).edit()
            .putString("last_error", "$location：${error.javaClass.simpleName} ${error.message.orEmpty().take(180)}")
            .putLong("error_at", System.currentTimeMillis()).apply() }
    }

    override fun onDestroy() {
        NightWatch.detach()
        launchService = null
        connectionState.value = false
        mainHandler.removeCallbacksAndMessages(null)
        importer.dispose()
        speedController.dispose()
        timeoutRunnable?.let(mainHandler::removeCallbacks)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile private var launchService: BambuAccessibilityService? = null
        suspend fun wakePhone(taskId: Long) = withContext(Dispatchers.Main) {
            val service = launchService ?: error("无障碍服务未连接，不能唤醒")
            check(ExecutionStore.activeTaskId(service) == taskId) { "任务已结束" }
            service.startActivity(android.content.Intent(service, com.example.bambuautoprint.ui.WakeActivity::class.java)
                .putExtra("taskId", taskId).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        suspend fun lockPhone(): Boolean = withContext(Dispatchers.Main) {
            val service = launchService ?: return@withContext false
            if (Build.VERSION.SDK_INT >= 28) service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        }
        /** 用户点击后立即请求打开 Handy，不建立打印执行记录。 */
        suspend fun prepareHandyLaunch() = withContext(Dispatchers.Main) {
            val service = launchService ?: error("请先开启并连接无障碍服务")
            check(ExecutionStore.activeTaskId(service) == null && !HandyCatalog.active(service) && !ExecutionStore.calibration(service) && !NightWatch.state.value.active) { "请先结束任务、导入、校准或夜间值守" }
            check(!service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "请先解锁手机" }
            val intent = service.packageManager.getLaunchIntentForPackage(AutomationForegroundService.BAMBU_PACKAGE) ?: error("未安装 Bambu Handy")
            service.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        /** 使用系统绑定的无障碍服务发起启动；返回仅表示已请求，必须等待 Handy 页面确认。 */
        suspend fun launchHandy(taskId: Long) = withContext(Dispatchers.Main) {
            val service = launchService ?: error("无障碍服务没有连接，无法自动启动 Handy")
            check(ExecutionStore.isRunning() && ExecutionStore.activeTaskId(service) == taskId && ExecutionStore.stage(service) == 0) { "启动请求已失效" }
            val intent = service.packageManager.getLaunchIntentForPackage(AutomationForegroundService.BAMBU_PACKAGE)
                ?: error("未找到 Bambu Handy")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            service.startActivity(intent)
        }

        private val connectionState = MutableStateFlow(false)
        val connected = connectionState.asStateFlow()
        private const val TAG = "BambuAccessibility"
        private const val STEP_TIMEOUT_MS = 45_000L
    }
}

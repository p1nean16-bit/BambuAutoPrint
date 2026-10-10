package com.example.bambuautoprint.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationManagerCompat
import com.example.bambuautoprint.data.PrintTask

/** 仅点击设备入口和速度档位；不会暂停、取消或再次提交打印。 */
class LayerSpeedController(
    private val service: AccessibilityService,
    private val log: (String) -> Unit,
    private val finish: (Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var task: PrintTask? = null
    private var started = 0L
    private var lastReadable = 0L
    private var lastAction = 0L
    private var popupOpened = 0L
    private var chosen = false
    private var target: String? = null
    private var initialConfirmed = false
    private var previous: LayerReading? = null
    private var lastLoggedLayer = -1
    private var missingSpeedSince = 0L
    private var stopped = false
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            runCatching { poll() }.onFailure { complete(false, "读取变速页面失败：${it.message}") }
            if (!stopped) handler.postDelayed(this, 2000)
        }
    }

    fun start(value: PrintTask) {
        dispose()
        task = value; stopped = false; started = SystemClock.elapsedRealtime(); lastReadable = started
        lastAction = 0; popupOpened = 0; chosen = false; target = null; initialConfirmed = false; previous = null; lastLoggedLayer = -1; missingSpeedSince = 0
        log("打印已提交；等待当前任务层数。第 ${value.slowFromLayer} 层起切换${value.afterSpeedMode}。请保持 Handy 前台和手机解锁。")
        update("等待层数 · 第 ${value.slowFromLayer} 层起${value.afterSpeedMode}")
        handler.post(tick)
    }

    private fun poll() {
        val value = task ?: return
        if (ExecutionStore.activeTaskId(service) != value.id) { dispose(); return }
        val now = SystemClock.elapsedRealtime()
        if (ExecutionStore.speedStopRequested(service)) { complete(false, "用户停止按层变速监控"); return }
        if (now - started > value.speedTimeoutMinutes * 60000L) { complete(false, "按层变速监控达到时间上限"); return }
        if (now - lastReadable > 90000L) { complete(false, "90 秒内无法核对当前打印机、模型和层数，请保持 Handy 设备页前台且手机解锁"); return }
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val root = service.rootInActiveWindow ?: return
        val children = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (root.packageName?.toString() != AutomationForegroundService.BAMBU_PACKAGE) return
            val nodes = mutableListOf(root)
            fun walk(node: AccessibilityNodeInfo) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { children += it; nodes += it; walk(it) }
            }
            walk(root)
            val visible = nodes.filter { it.isVisibleToUser }
            fun labels(node: AccessibilityNodeInfo) = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).map(String::trim).filter(String::isNotEmpty)
            val texts = visible.flatMap(::labels)
            fun click(node: AccessibilityNodeInfo?): Boolean {
                var candidate = node
                repeat(5) {
                    if (candidate?.isClickable == true && candidate!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { lastAction = now; return true }
                    candidate = candidate?.parent
                }
                return false
            }
            fun exact(vararg names: String) = visible.firstOrNull { node -> labels(node).any { text -> names.any { it.equals(text, true) } } }
            val modeNodes = visible.mapNotNull { node -> labels(node).firstNotNullOfOrNull(LayerSpeedRules::mode)?.let { it to node } }
            val isPopup = modeNodes.map { it.first }.distinct().size >= 2

            // 只有自己刚刚打开的速度选择器才能处理；未知弹窗不点击“确定”。
            if (popupOpened > 0L) {
                if (now - popupOpened > 30000L) { complete(false, "速度切换后未收到档位反馈"); return }
                if (isPopup) {
                    if (!chosen && now - lastAction >= 1500 && click(modeNodes.firstOrNull { it.first == target }?.second)) chosen = true
                    else if (chosen && now - lastAction >= 1500) {
                        val confirm = exact("确定", "确认", "Confirm", "OK")
                        if (!click(confirm)) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); lastAction = now }
                    }
                    return
                }
            } else if (isPopup) return

            val printerMatches = texts.any { it.equals(value.printerIdentifier, true) }
            val modelMatches = texts.any { it.equals(value.modelName, true) || it.equals(value.modelName + ".3mf", true) || it.equals(value.modelName + ".gcode", true) }
            val reading = LayerSpeedRules.parse(texts)
            if (!printerMatches || !modelMatches || reading == null) {
                if (popupOpened > 0L || now - lastAction < 6000) return
                // 自动转到目标设备，不进入文件列表，也不碰打印按钮。
                if (!printerMatches && click(exact(value.printerIdentifier))) return
                if (reading == null) click(exact("设备", "Devices"))
                return
            }
            if (texts.any { it in setOf("离线", "未连接", "Offline") }) { complete(false, "设备页显示打印机离线"); return }
            if (previous != null && (reading.total != previous!!.total || reading.current < previous!!.current)) {
                complete(false, "层数回退或总层数变化，当前打印可能已更换"); return
            }
            previous = reading; lastReadable = now
            if (value.slowFromLayer > reading.total) { complete(false, "设定减速层 ${value.slowFromLayer} 超过总层数 ${reading.total}"); return }
            if (reading.current == 0) return // 等待预热/校准结束，避免将准备阶段当成第一层。
            val desired = LayerSpeedRules.desired(reading.current, value.slowFromLayer, value.beforeSpeedMode, value.afterSpeedMode)
            val observed = modeNodes.map { it.first }.distinct().singleOrNull()
            if (observed == null && popupOpened == 0L) {
                if (missingSpeedSince == 0L) missingSpeedSince = now
                if (now - missingSpeedSince > 30000L) { complete(false, "无法读取设备页的当前速度档位，此 Handy 页面尚未适配"); return }
            } else missingSpeedSince = 0L
            if (popupOpened > 0L) {
                if (observed != target) return // 点击成功不代表打印机接受，必须读到更新后的档位。
                log("设备页反馈速度：$observed，当前 ${reading.current}/${reading.total} 层")
                popupOpened = 0; chosen = false
                if (target == value.afterSpeedMode) { complete(true, "已于观察到的第 ${reading.current} 层确认切换为${value.afterSpeedMode}"); return }
                initialConfirmed = true; target = null
            }
            if (observed == desired) {
                if (desired == value.afterSpeedMode) { complete(true, "第 ${reading.current} 层已确认使用${value.afterSpeedMode}"); return }
                if (!initialConfirmed) { initialConfirmed = true; log("已确认前段速度为${value.beforeSpeedMode}") }
                if (lastLoggedLayer < 0 || reading.current - lastLoggedLayer >= 10) {
                    lastLoggedLayer = reading.current; log("层数 ${reading.current}/${reading.total}，等待第 ${value.slowFromLayer} 层减速")
                    update("${reading.current}/${reading.total} 层 · 第 ${value.slowFromLayer} 层起${value.afterSpeedMode}")
                }
                return
            }
            if (now - lastAction < 2000) return
            if (click(exact("速度", "打印速度", "Print Speed", "Speed") ?: modeNodes.singleOrNull()?.second)) {
                target = desired; chosen = false; popupOpened = now
                log("请求切换${desired}；当前 ${reading.current}/${reading.total} 层，等待档位反馈")
            }
        } finally { children.asReversed().forEach { it.recycle() }; root.recycle() }
    }

    private fun update(message: String) {
        val value = task ?: return
        PrintNotifications.post(service, PrintNotifications.RUNNING_ID,
            PrintNotifications.running(service, "${value.name}：$message", value.id, "按层变速监控中"))
    }
    private fun complete(success: Boolean, message: String) {
        if (stopped) return
        dispose()
        finish(success, if (success) "$message；变速监控结束，打印继续" else "$message；打印已提交，仍按打印机当前档位继续，请检查设备")
    }
    fun dispose() { stopped = true; handler.removeCallbacks(tick) }
}

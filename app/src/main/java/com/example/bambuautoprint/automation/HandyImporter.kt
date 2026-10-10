package com.example.bambuautoprint.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bambuautoprint.data.HandyCatalog
import com.example.bambuautoprint.data.HandyResource
import com.example.bambuautoprint.ui.MainActivity

/** 有界的资源导入器：仅打开列表、翻页和读取；不访问打印准备/确认按钮。 */
class HandyImporter(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var session = 0L
    private var routeIndex = 0
    private var stepStarted = 0L
    private var nextAction = 0L
    private var pages = 0
    private var backAttempts = 0
    private var unchanged = 0
    private var lastPage = ""
    private val collected = linkedMapOf<String, HandyResource>()
    private var scheduled = false
    private val poll = object : Runnable {
        override fun run() {
            scheduled = false
            if (!HandyCatalog.active(service)) return
            tick()
            if (HandyCatalog.active(service)) schedule()
        }
    }

    fun onPageChanged() { if (HandyCatalog.active(service)) schedule() }
    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed(poll, 800)
    }
    fun dispose() { handler.removeCallbacks(poll); scheduled = false }

    private fun tick() {
        val now = System.currentTimeMillis()
        val started = HandyCatalog.started(service)
        if (session != started) {
            session = started; routeIndex = 0; pages = 0; backAttempts = 0; unchanged = 0
            lastPage = ""; collected.clear(); stepStarted = now; nextAction = now
        }
        if (now - started > 100_000) { complete("导入超时。已保留 ${collected.size} 项；请确认 Handy 已登录且页面可以读取。"); return }
        if (now < nextAction) return
        val root = service.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != AutomationForegroundService.BAMBU_PACKAGE) return
            val texts = textValues(root)
            if (texts.any { it == "立即登录" || it == "登录/注册" }) {
                complete("Bambu Handy 尚未登录，请登录后再导入。"); return
            }
            val kind = HandyCatalog.kind(service)
            val source = HandyCatalog.source(service)
            val route = listOf(listOf("回到主页")) + when {
                kind == "PRINTER" -> listOf(listOf("设备", "Devices"))
                source == "设备文件" -> listOf(listOf("设备", "Devices"), listOf(HandyCatalog.printer(service)), listOf("打印机设置"), listOf("文件管理"), listOf("模型"))
                source == "打印历史" -> listOf(listOf("我的", "Me"), listOf("打印历史", "打印记录", "历史记录"))
                else -> listOf(listOf("我的", "Me"), listOf("收藏 & 点赞", "收藏&点赞", "收藏与点赞", "收藏", "我的收藏", "Favorites"), listOf("收藏列表"), listOf("收藏模型"))
            }
            if (routeIndex < route.size) {
                HandyCatalog.progress("正在打开 ${route[routeIndex].firstOrNull().orEmpty()}…", collected.size)
                if (route[routeIndex].any { HandyNavigation.special(service, root, it, HandyCatalog.printer(service)) ?: clickExact(root, it) }) {
                    routeIndex++; stepStarted = now; nextAction = now + 1800; return
                }
                if (now - stepStarted > 12_000) {
                    if (routeIndex == 0 && backAttempts < 2) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        backAttempts++; stepStarted = now; nextAction = now + 1500
                    } else complete("未找到“${route[routeIndex].firstOrNull()}”入口。当前 Handy 版本的这个入口尚未适配，可选其他来源重试。")
                }
                return
            }
            // 到达指定列表后采集卡片；不将导航栏、按钮文案或数字统计导入为资源。
            val items = CatalogNodeParser.extract(root, kind, source)
            items.forEach { collected[it.name] = it }
            HandyCatalog.merge(service, items)
            val signature = texts.joinToString("|")
            unchanged = if (signature == lastPage) unchanged + 1 else 0
            lastPage = signature
            HandyCatalog.progress("正在读取${if (kind == "PRINTER") "设备" else source} · 已发现 ${collected.size} 项", collected.size)
            if (pages >= 12 || unchanged >= 2) {
                complete(if (collected.isEmpty()) "页面未暴露可识别的资源卡片，未导入任何内容。可尝试另一个来源。" else "导入完成：${collected.size} 项。请在资源库确认名称并选择目标。")
                return
            }
            // 不对水平页签容器翻页，避免“模型”被翻到“收藏夹”。
            val scroller = find(root) { it.isVisibleToUser && it.isScrollable &&
                (it.actionList.any { action -> action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id } ||
                    it.className?.toString()?.let { name -> name.contains("RecyclerView") || name.contains("ListView") || name.endsWith("ScrollView") && !name.contains("Horizontal") } == true) }
            if (scroller == null || !scroller.performAction(if (scroller.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id }) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                complete(if (collected.isEmpty()) "没有识别到资源。列表可能为空，或 Handy 未提供可读控件。" else "导入完成：${collected.size} 项。请在资源库确认后选择。")
            } else { pages++; nextAction = now + 1800 }
        } catch (error: Exception) {
            Log.e("HandyImporter", "导入失败", error)
            complete("导入失败：${error.message ?: "页面读取异常"}；已保留 ${collected.size} 项")
        } finally {
            root.recycle()
        }
    }

    private fun complete(message: String) {
        // 完整导入后用本轮列表替换同来源缓存，清除旧版本误导入项。
        if (message.startsWith("导入完成") && collected.isNotEmpty()) {
            HandyCatalog.replaceSource(service, HandyCatalog.kind(service), HandyCatalog.source(service), collected.values.toList())
        }
        HandyCatalog.finish(service, message, collected.size)
        PrintNotifications.catalogResult(service, message)
        dispose()
        runCatching {
            service.startActivity(Intent(service, MainActivity::class.java).setAction(MainActivity.ACTION_IMPORT_FINISHED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { Log.w("HandyImporter", "无法自动返回，请用户切回工具", it) }
    }

    private fun clickExact(root: AccessibilityNodeInfo, label: String): Boolean {
        if (label.isBlank() || label in setOf("打印", "准备打印", "开始打印", "确认打印")) return false
        val node = find(root) { it.isVisibleToUser && (it.text?.toString()?.trim() == label || it.contentDescription?.toString()?.trim() == label) } ?: return false
        var parent: AccessibilityNodeInfo? = node
        repeat(5) {
            if (parent?.isEnabled == true && parent?.isClickable == true && parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent?.parent
        }
        return false
    }
    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; find(child, predicate)?.let { return it } }
        return null
    }
    private fun textValues(node: AccessibilityNodeInfo): List<String> {
        val values = mutableListOf<String>()
        if (node.isVisibleToUser) node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
        for (i in 0 until node.childCount) node.getChild(i)?.let { values += textValues(it) }
        return values
    }
}

/** 标题提取保持保守；无法证明是卡片时不把整页文本当成设备/模型。 */
object CatalogNodeParser {
    private val ignored = setOf("成功", "失败", "已取消", "取消打印", "评分", "历史", "状态", "日期筛选", "没有更多了", "收藏夹", "文件管理", "延时摄影", "编辑", "收藏 & 点赞", "收藏&点赞", "收藏与点赞", "设备", "我的", "模型", "首页", "社区", "收藏", "我的收藏", "打印历史", "打印记录", "文件", "云文件", "设备文件", "全部", "最近", "更多", "返回", "搜索", "排序", "筛选", "添加设备", "添加打印机", "在线", "离线", "空闲", "打印中", "打印", "准备打印", "打印当前盘", "开始打印", "确认打印", "下一步", "确定", "取消", "知道了", "AMS", "耗材", "打印板", "加载中", "暂无数据", "暂无收藏", "刷新", "推荐", "热门", "最新", "关注", "消息", "设置", "个人主页", "下载", "分享", "点赞", "评论", "Devices", "Me", "Favorites")
    private val hardware = Regex("(?i)\\b(?:X1[CE]?|P1[PS]?|P2S|A1|H2[DSC]?)(?:\\s+(?:Carbon|Mini|Combo))?\\b")
    private fun title(value: String): Boolean = value.length in 2..100 && value !in ignored &&
        !value.matches(Regex("[\\d.,%:/ +−-]+[kKwWmMhH秒分时℃°]*")) &&
        !value.matches(Regex("(?i)(PLA|PETG|ABS|TPU|ASA)(\\s.*)?")) &&
        !value.contains("层高") && !value.contains("填充") && !value.startsWith("第1盘") &&
        !value.contains("登录") && !value.contains("注册") && !value.contains("暂无") && !value.contains("请先")

    /** 独立的纯文本规则，供导入器和单元测试复用。 */
    fun candidateTitle(kind: String, values: List<String>): String? {
        val isPrinter = values.any { hardware.containsMatchIn(it) || it in setOf("在线", "离线", "空闲", "打印中") }
        if (kind == "PRINTER" && !isPrinter) return null
        return values.firstOrNull { it.endsWith(".3mf", true) || it.endsWith(".gcode", true) } ?: values.firstOrNull(::title)
    }

    fun extract(root: AccessibilityNodeInfo, kind: String, source: String): List<HandyResource> {
        val result = mutableListOf<HandyResource>()
        fun labels(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
            if (depth > 10 || !node.isVisibleToUser) return emptyList()
            val own = listOfNotNull(node.text?.toString()?.trim()?.takeIf(String::isNotBlank))
            return own + (0 until node.childCount).flatMap { node.getChild(it)?.let { child -> labels(child, depth + 1) }.orEmpty() }
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 24 || !node.isVisibleToUser) return
            val values = labels(node).distinct()
            if (node.isClickable && values.size in 1..15 && (kind == "PRINTER" || values.size >= 2 || values.any { it.endsWith(".3mf", true) || it.endsWith(".gcode", true) })) {
                candidateTitle(kind, values)?.let { name ->
                        result += HandyResource(kind, name, source, values.filterNot { it == name }.take(3).joinToString(" · "))
                    if (kind == "MODEL") return // 已识别整张模型卡，不再将作者等子控件重复导入。
                }
            }
            // 部分卡片本身不可点击，但标题控件带有明确 title/name 的 resource-id。
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val name = node.text?.toString()?.trim().orEmpty()
            if (title(name) && (id.contains("title") || id.contains("device_name") || id.contains("model_name"))) {
                result += HandyResource(kind, name, source, "页面标题识别 · 请确认")
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root)
        return result.distinctBy { it.name }.take(100)
    }
}

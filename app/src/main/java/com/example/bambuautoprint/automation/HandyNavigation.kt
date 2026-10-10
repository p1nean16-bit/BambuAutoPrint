package com.example.bambuautoprint.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/** 导入与执行共用导航；只点击入口，不点击打印按钮。 */
object HandyNavigation {
    fun route(source: String, printer: String): List<String> {
        if (source.startsWith("设备>")) return listOf("回到主页", "设备", printer, "打印机设置", "文件管理", "模型").filter(String::isNotBlank)
        val parts = source.split('>').map(String::trim).filter(String::isNotBlank)
        val favorites = parts.any { it in setOf("收藏 & 点赞", "收藏&点赞", "我的收藏", "收藏", "收藏列表") }
        return listOf("回到主页") + if (favorites) listOf("我的", "收藏 & 点赞", "收藏列表", "收藏模型") else parts

    }

    fun special(service: AccessibilityService, root: AccessibilityNodeInfo, label: String, printer: String): Boolean? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) {
            if (n.isVisibleToUser) nodes += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
        }
        visit(root)
        fun has(text: String) = nodes.any { it.text?.toString()?.trim() == text }
        fun click(node: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = node
            repeat(5) {
                if (current?.isEnabled == true && current?.isClickable == true && current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                current = current?.parent
            }
            return false
        }
        if (label == "回到主页") {
            val bounds = Rect(); root.getBoundsInScreen(bounds)
            fun bottomTab(text: String) = nodes.any { n ->
                val r = Rect(); n.getBoundsInScreen(r)
                (n.text?.toString()?.trim() == text || n.contentDescription?.toString()?.trim() == text) && r.centerY() > bounds.top + bounds.height() * 0.80
            }
            if (bottomTab("模型") && bottomTab("设备") && bottomTab("我的")) return true
            // 普通应用不能强制停止 Handy；有界导航调用者控制返回重试和总超时。
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.i("HandyNavigation", "返回 Handy 主页面")
            return false
        }
        if (label == "收藏模型") {
            if (!has("收藏夹") || !has("收藏")) return false
            val node = nodes.firstOrNull { it.text?.toString()?.trim() == "模型" || it.contentDescription?.toString()?.trim() == "模型" } ?: return false
            return node.isSelected || click(node)
        }
        // 已处于目标设备首页时不再点名称，避免打开切换设备菜单。
        val dashboard = has("设备") && has("我的") && (has("在线") || has("离线"))
        if (label == printer && printer.isNotBlank() && dashboard && has(printer)) return true
        if (label == "收藏列表") {
            // 仅选择“收藏”页签，绝不进入收藏夹。
            val node = nodes.firstOrNull { it.text?.toString()?.trim() == "收藏" || it.contentDescription?.toString()?.trim() == "收藏" } ?: return false
            return node.isSelected || click(node)
        }
        if (label != "打印机设置") return null
        if (has("文件管理") && has("设置")) return true
        if (!dashboard || (printer.isNotBlank() && !has(printer))) return false
        val named = nodes.firstOrNull { it.contentDescription?.toString()?.trim() in setOf("设置", "设备设置", "打印机设置") || it.text?.toString()?.trim() == "设置" }
        if (named != null) return click(named)
        // 根据用户截图的顶部设置图标区域，仅寻找该区域内可点击的图标节点。
        // 没有可读节点时返回失败，不向屏幕盲发坐标点击。
        val screen = Rect(); root.getBoundsInScreen(screen)
        val candidates = nodes.filter { n ->
            val b = Rect(); n.getBoundsInScreen(b)
            n.isClickable && n.isEnabled && b.width() in 1..(screen.width() / 6) && b.height() in 1..(screen.height() / 10) &&
                b.centerX() > screen.left + screen.width() * 0.70 && b.centerX() < screen.left + screen.width() * 0.86 &&
                b.centerY() > screen.top + screen.height() * 0.035 && b.centerY() < screen.top + screen.height() * 0.12
        }
        val node = candidates.minByOrNull { val b = Rect(); it.getBoundsInScreen(b); b.width() * b.height() } ?: return false
        Log.i("HandyNavigation", "目标设备顶部设置入口：${node.viewIdResourceName}")
        return click(node)
    }
}

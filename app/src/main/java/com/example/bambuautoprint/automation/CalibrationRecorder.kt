package com.example.bambuautoprint.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 将手动校准时点击的文字、viewId、屏幕位置写为 JSONL，供后续形成可编辑步骤。 */
object CalibrationRecorder {
    fun capture(service: AccessibilityService, event: AccessibilityEvent) {
        val root = service.rootInActiveWindow ?: return
        val source = event.source
        val rect = Rect()
        source?.getBoundsInScreen(rect)
        val metrics = service.resources.displayMetrics
        val centerX = (rect.exactCenterX() / metrics.widthPixels.coerceAtLeast(1)).coerceIn(0f, 1f)
        val centerY = (rect.exactCenterY() / metrics.heightPixels.coerceAtLeast(1)).coerceIn(0f, 1f)
        val row = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("package", event.packageName?.toString().orEmpty())
            .put("event", AccessibilityEvent.eventTypeToString(event.eventType))
            .put("text", event.text.joinToString(" "))
            .put("viewId", if (Build.VERSION.SDK_INT >= 18) source?.viewIdResourceName.orEmpty() else "")
            .put("class", source?.className?.toString().orEmpty())
            .put("contentDescription", source?.contentDescription?.toString().orEmpty())
            .put("clickable", source?.isClickable ?: false)
            .put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
            .put("centerRatio", "${"%.4f".format(Locale.ROOT, centerX)},${"%.4f".format(Locale.ROOT, centerY)}")
        try {
            File(service.filesDir, "calibration_steps.jsonl").appendText(row.toString() + "\n")
        } catch (_: Exception) {
            // 校准日志失败不影响正常的手动操作。
        } finally {
            source?.recycle()
            root.recycle()
        }
    }
}

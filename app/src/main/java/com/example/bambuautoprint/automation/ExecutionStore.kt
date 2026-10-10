package com.example.bambuautoprint.automation

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** 轻量执行状态；持久化 taskId 和阶段，避免进程重建后重复提交打印。 */
object ExecutionStore {
    private const val PREFS = "automation_state"
    private const val KEY_TASK_ID = "active_task_id"
    private const val KEY_STAGE = "stage"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_CALIBRATION = "calibration_mode"
    private const val KEY_SOURCE_STEP = "source_step"
    private const val KEY_SLOT_CHOSEN = "slot_chosen"
    private const val KEY_APP_FOREGROUND = "app_foreground"
    private const val KEY_PRINTER_CAPTURE_ACTIVE = "printer_capture_active"
    private const val KEY_PRINTER_CANDIDATES = "printer_candidates"
    private const val KEY_PRINTER_CAPTURE_STARTED_AT = "printer_capture_started_at"
    private val running = AtomicBoolean(false)

    /**
     * 同一进程内原子加锁，并检查持久化的上次执行记录。
     * 进程被系统杀掉时不自动重放未知阶段的打印任务，避免重复下发。
     */
    @Synchronized
    fun acquire(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (activeTaskId(context) != null) {
            running.set(false)
            return false
        }
        return true
    }

    fun release() { running.set(false) }
    fun isRunning(): Boolean = running.get()
    fun requestSpeedStop(context: Context) = prefs(context).edit().putBoolean("speed_stop", true).apply()
    fun speedStopRequested(context: Context) = prefs(context).getBoolean("speed_stop", false)

    /** 在 UI 自动化开始前同步落盘，确保进程异常退出时能识别未完成任务。 */
    fun begin(context: Context, taskId: Long): Boolean = prefs(context).edit()
        .putLong(KEY_TASK_ID, taskId).putInt(KEY_STAGE, 0)
        .putLong(KEY_STARTED_AT, System.currentTimeMillis())
        .putInt(KEY_SOURCE_STEP, 0).putBoolean(KEY_SLOT_CHOSEN, false)
        .putBoolean(KEY_APP_FOREGROUND, false)
        .putBoolean("speed_stop", false)
        .putBoolean(KEY_CALIBRATION, false)
        .putBoolean(KEY_PRINTER_CAPTURE_ACTIVE, false).commit()

    fun activeTaskId(context: Context): Long? = prefs(context).getLong(KEY_TASK_ID, -1L).takeIf { it > 0 }
    fun stage(context: Context): Int = prefs(context).getInt(KEY_STAGE, 0)
    fun setStage(context: Context, stage: Int) = prefs(context).edit().putInt(KEY_STAGE, stage).apply()
    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED_AT, 0L)
    fun sourceStep(context: Context): Int = prefs(context).getInt(KEY_SOURCE_STEP, 0)
    fun setSourceStep(context: Context, step: Int) = prefs(context).edit().putInt(KEY_SOURCE_STEP, step).apply()
    fun slotChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_SLOT_CHOSEN, false)
    fun setSlotChosen(context: Context, chosen: Boolean) = prefs(context).edit().putBoolean(KEY_SLOT_CHOSEN, chosen).apply()
    fun appForeground(context: Context): Boolean = prefs(context).getBoolean(KEY_APP_FOREGROUND, false)
    fun setAppForeground(context: Context, foreground: Boolean) = prefs(context).edit().putBoolean(KEY_APP_FOREGROUND, foreground).apply()
    fun clear(context: Context) = prefs(context).edit()
        .remove(KEY_TASK_ID).remove(KEY_STAGE).remove(KEY_STARTED_AT)
        .remove(KEY_SOURCE_STEP).remove(KEY_SLOT_CHOSEN).remove(KEY_APP_FOREGROUND).apply()

    /** 只有进程内没有正在执行的任务时，才允许用户清理上次异常中断记录。 */
    @Synchronized
    fun clearInterrupted(context: Context): Long? {
        if (running.get()) return null
        val taskId = activeTaskId(context) ?: return null
        val cleared = prefs(context).edit()
            .remove(KEY_TASK_ID).remove(KEY_STAGE).remove(KEY_STARTED_AT)
            .remove(KEY_SOURCE_STEP).remove(KEY_SLOT_CHOSEN).remove(KEY_APP_FOREGROUND)
            .commit()
        return taskId.takeIf { cleared }
    }

    fun setCalibration(context: Context, active: Boolean) = prefs(context).edit()
        .putBoolean(KEY_CALIBRATION, active).apply()
    fun calibration(context: Context): Boolean = prefs(context).getBoolean(KEY_CALIBRATION, false)

    /** 用户主动发起设备识别时开启；只收集 Handy 可点击控件上显示的名称。 */
    fun beginPrinterCapture(context: Context) = prefs(context).edit()
        .putBoolean(KEY_PRINTER_CAPTURE_ACTIVE, true)
        .putLong(KEY_PRINTER_CAPTURE_STARTED_AT, System.currentTimeMillis())
        .putString(KEY_PRINTER_CANDIDATES, "").apply()

    fun printerCaptureActive(context: Context): Boolean {
        val state = prefs(context)
        if (!state.getBoolean(KEY_PRINTER_CAPTURE_ACTIVE, false)) return false
        val startedAt = state.getLong(KEY_PRINTER_CAPTURE_STARTED_AT, 0L)
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt > PRINTER_CAPTURE_TIMEOUT_MS) {
            endPrinterCapture(context)
            return false
        }
        return true
    }

    /** 保存当前设备页候选名称，不保存整页文本、截图或账号信息。 */
    fun savePrinterCandidates(context: Context, labels: List<String>) {
        if (!printerCaptureActive(context) || labels.isEmpty()) return
        prefs(context).edit().putString(
            KEY_PRINTER_CANDIDATES,
            labels.map { it.replace('\n', ' ').trim() }.filter(String::isNotBlank).distinct().joinToString("\n")
        ).apply()
    }

    fun printerCandidates(context: Context): List<String> = prefs(context)
        .getString(KEY_PRINTER_CANDIDATES, "").orEmpty()
        .lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()

    fun endPrinterCapture(context: Context) =
        prefs(context).edit().putBoolean(KEY_PRINTER_CAPTURE_ACTIVE, false).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PRINTER_CAPTURE_TIMEOUT_MS = 5 * 60_000L
}

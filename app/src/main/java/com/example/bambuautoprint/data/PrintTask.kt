package com.example.bambuautoprint.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 重复规则：ONCE、DAILY、WEEKLY、CUSTOM。周掩码按周一到周日的 bit 0..6 表示。 */
@Entity(tableName = "print_tasks")
data class PrintTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerAtMillis: Long,
    val repeatMode: String = "ONCE",
    val weekdaysMask: Int = 0,
    val printerIdentifier: String,
    val modelName: String,
    val modelSource: String = "我的>收藏 & 点赞",
    val printProfile: String = "好评最多",
    val bedType: String = "",
    val materialType: String = "PLA",
    val amsSlot: String = "",
    val useAms: Boolean = true,
    val requireCalibration: Boolean = false,
    val preparePrintLabel: String = "准备打印",
    val nextStepLabel: String = "下一步",
    val tutorialConfirmLabel: String = "确定",
    val queueDismissLabel: String = "知道了",
    val printCurrentPlateLabel: String = "打印当前盘",
    val finalConfirmLabel: String = "确认打印",
    /** 可选后备坐标 JSON：{ "准备打印": [0.5, 0.92] }，坐标为屏幕宽高比例。 */
    val coordinateOverridesJson: String = "",
    val retryCount: Int = 1,
    val timeoutMinutes: Int = 20,
    val enabled: Boolean = true,
    /** 到达 slowFromLayer（含本层）后切换低速；只作用于已确认的当前打印。 */
    val adaptiveSpeedEnabled: Boolean = false,
    val slowFromLayer: Int = 151,
    val beforeSpeedMode: String = "标准",
    val afterSpeedMode: String = "静音",
    val speedTimeoutMinutes: Int = 480
)

@Entity(
    tableName = "execution_logs",
    indices = [Index(value = ["taskId"]), Index(value = ["createdAtMillis"])]
)
data class ExecutionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val level: String,
    val stage: String,
    val message: String,
    val screenshotPath: String? = null
)

package com.example.bambuautoprint.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.bambuautoprint.ui.MainActivity

object PrintNotifications {
    const val CHANNEL_ID = "print_automation"
    const val RUNNING_ID = 4100

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "定时打印", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "打印任务运行状态与结果" }
            )
        }
    }

    fun running(context: Context, taskName: String, taskId: Long, title: String = "任务进行中 · 尚未确认打印") = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
        .setContentTitle(title)
        .setContentText(taskName)
        .setContentIntent(PendingIntent.getActivity(context, taskId.toInt(), Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_VIEW_LOGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    fun clearRunning(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RUNNING_ID)
    }

    fun waitingForUser(context: Context, taskName: String, taskId: Long) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("需要打开 Bambu Handy")
            .setContentText("$taskName：若 App 未自动前台显示，请点击此通知继续")
            .setContentIntent(continuePendingIntent(context, taskId))
            .addAction(android.R.drawable.ic_menu_view, "打开 Bambu Handy", continuePendingIntent(context, taskId))
            .setOngoing(true)
            .build()
        post(context, RUNNING_ID, notification)
    }

    fun result(context: Context, taskId: Long, taskName: String, success: Boolean, message: String, title: String? = null) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert)
            .setContentTitle(title ?: if (success) "打印任务已提交" else "任务失败")
            .setContentText("$taskName：$message")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$taskName：$message"))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, taskId.toInt(), Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_VIEW_LOGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        post(context, taskId.toInt(), notification)
    }

    fun catalogResult(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Handy 资源导入结果")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 4200, Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_IMPORT_FINISHED).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        post(context, 4200, notification)
    }

    fun dayPlan(context: Context, planId: Long, index: Int, name: String, pickup: Boolean, message: String) {
        val id = (planId xor (planId ushr 32)).toInt() xor (index * 2 + if (pickup) 1 else 0)
        val pending = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DAY_PLAN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(if (pickup) "到预定收菜时间了 · $name" else "到计划开打时间了 · $name")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending).setAutoCancel(true).build()
        post(context, id, notification)
    }

    fun nightWatch(context: Context, active: Boolean, message: String) {
        val pending = PendingIntent.getActivity(context, 4300, Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_NIGHT_WATCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(if (active) "夜间值守已开启" else "夜间值守已停止")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending).setOngoing(active).setSilent(true).build()
        post(context, 4300, notification)
    }

    /** 权限可随时被撤回；既检查权限，也处理检查后撤回的竞态。 */
    fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("PrintNotifications", "通知权限未授予，结果仍保存到日志")
            return
        }
        try { NotificationManagerCompat.from(context).notify(id, notification) }
        catch (error: SecurityException) { Log.w("PrintNotifications", "通知权限被撤回", error) }
    }

    private fun continuePendingIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CONTINUE_AUTOMATION)
            .putExtra(AutomationForegroundService.EXTRA_TASK_ID, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, taskId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

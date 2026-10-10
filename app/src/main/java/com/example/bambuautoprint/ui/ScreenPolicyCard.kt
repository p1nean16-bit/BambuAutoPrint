package com.example.bambuautoprint.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.bambuautoprint.automation.ScreenPolicy

@Composable fun ScreenPolicyCard(context: Context, tell: (String) -> Unit) {
    val prefs = remember { ScreenPolicy.prefs(context) }
    var wake by remember { mutableStateOf(prefs.getBoolean("wake", false)) }
    var sleep by remember { mutableStateOf(prefs.getBoolean("sleep", false)) }
    SoftCard {
        SectionTitle("屏幕与夜间运行", "可选 · 不必整晚显示固定画面")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("到点尝试唤醒手机", Modifier.weight(1f))
            Switch(wake, { enabled ->
                if (enabled && context.getSystemService(KeyguardManager::class.java).isDeviceSecure) tell("手机有安全锁，无法无人解锁；请自行保持解锁")
                else if (prefs.edit().putBoolean("wake", enabled).commit()) wake = enabled else tell("设置保存失败")
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动化结束后锁屏息屏", Modifier.weight(1f))
            Switch(sleep, { enabled ->
                if (enabled && Build.VERSION.SDK_INT < 28) tell("自动锁屏需要 Android 9 或更高")
                else if (prefs.edit().putBoolean("sleep", enabled).commit()) sleep = enabled else tell("设置保存失败")
            })
        }
        Text("唤醒需要无障碍连接、精确闹钟和后台弹出权限，且没有密码/指纹安全锁。此功能需实机确认。结束指提交或自动化失败，不是实体打印结束；按层变速会等监控结束后再锁屏。", style = MaterialTheme.typography.bodySmall)
        Text("这是与亮屏值守不同的运行方式。开启后可自行息屏等待；多任务仍需人工收菜换板。锁屏后再次无人启动需开启唤醒，系统授权弹窗仍要提前处理。", style = MaterialTheme.typography.bodySmall)
    }
}

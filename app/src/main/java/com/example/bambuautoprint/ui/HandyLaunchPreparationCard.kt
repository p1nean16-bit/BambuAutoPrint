package com.example.bambuautoprint.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bambuautoprint.automation.BambuAccessibilityService
import kotlinx.coroutines.launch

/** 厂商“始终允许打开”没有通用读取接口；显示用户确认，不能冒充系统权限检测。 */
@Composable fun HandyLaunchPreparationCard(context: Context, tell: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("launch_preparation", Context.MODE_PRIVATE) }
    var confirmed by remember { mutableStateOf(prefs.getBoolean("user_confirmed", false)) }
    val scope = rememberCoroutineScope()
    SoftCard {
        SectionTitle("自动打开 Handy 授权", if (confirmed) "用户已确认 · 仍需定时验证" else "睡前先完成启动确认")
        Text("若系统提示“竹竹想要打开 Bambu Handy”，请勾选“始终允许打开”，再点“打开”。这项选择必须由你完成。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            scope.launch {
                runCatching { BambuAccessibilityService.prepareHandyLaunch() }
                    .onSuccess { tell("已请求打开 Handy；若出现系统弹窗，请勾选始终允许打开") }
                    .onFailure { tell("请求失败：${it.message}") }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("立即请求打开 Handy · 不打印") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(confirmed, { value ->
                if (prefs.edit().putBoolean("user_confirmed", value).commit()) confirmed = value
                else tell("无法保存确认状态，请重试")
            })
            Text("我已勾选始终允许打开，并成功进入 Handy", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Text("此项是你的手动确认，软件无法读取该厂商授权。重新安装、清除数据或系统设置变化后可能需要再次确认。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { tell("无法打开应用设置：${it.message}") }
        }) { Text("打开竹竹应用设置") }
    }
}

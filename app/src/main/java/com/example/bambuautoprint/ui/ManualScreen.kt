package com.example.bambuautoprint.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 说明书同时打包在 APK 内，离线可读。 */
@Composable fun ManualScreen(context: Context) {
    val text = remember { runCatching { context.assets.open("manual.txt").bufferedReader().use { it.readText() } }.getOrElse { "说明书读取失败：${it.message}" } }
    text.split(Regex("\\n\\s*\\n")).forEach { paragraph ->
        if (paragraph.startsWith("#")) {
            val lines = paragraph.split("\n", limit = 2)
            Text(lines.first().trimStart('#', ' '), style = MaterialTheme.typography.titleLarge)
            if (lines.size > 1) SoftCard { Text(lines[1], style = MaterialTheme.typography.bodyMedium) }
        }
        else SoftCard { Text(paragraph, style = MaterialTheme.typography.bodyMedium) }
    }
}

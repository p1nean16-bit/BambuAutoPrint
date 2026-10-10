package com.example.bambuautoprint.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 只存页面上可见的资源名称，不读取 Handy 的账号凭据和私有文件。 */
data class HandyResource(val kind: String, val name: String, val source: String, val detail: String = "", val importedAt: Long = System.currentTimeMillis())
data class ImportStatus(val active: Boolean = false, val message: String = "尚未导入", val count: Int = 0)

object HandyCatalog {
    private val resourceFlow = MutableStateFlow<List<HandyResource>>(emptyList())
    private val statusFlow = MutableStateFlow(ImportStatus())
    val resources = resourceFlow.asStateFlow()
    val status = statusFlow.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("handy_catalog", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val array = runCatching { JSONArray(prefs(context).getString("resources", "[]")) }.getOrDefault(JSONArray())
        resourceFlow.value = (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).let { HandyResource(it.getString("kind"), it.getString("name"), it.getString("source"), it.optString("detail"), it.optLong("at")) } }.getOrNull()
        }
    }

    @Synchronized fun merge(context: Context, items: List<HandyResource>) {
        val merged = (resourceFlow.value + items).distinctBy { Triple(it.kind, it.source, it.name.replace(Regex("\\s+"), "").lowercase()) }
        persist(context, merged)
    }

    @Synchronized fun replaceSource(context: Context, kind: String, source: String, items: List<HandyResource>) {
        val retained = resourceFlow.value.filterNot { it.kind == kind && it.source == source }
        persist(context, retained + items.distinctBy { it.name.replace(Regex("\\s+"), "").lowercase() })
    }

    @Synchronized fun remove(context: Context, item: HandyResource) {
        persist(context, resourceFlow.value.filterNot { it.kind == item.kind && it.source == item.source && it.name == item.name })
    }

    private fun persist(context: Context, items: List<HandyResource>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("kind", it.kind).put("name", it.name).put("source", it.source).put("detail", it.detail).put("at", it.importedAt)) }
        check(prefs(context).edit().putString("resources", array.toString()).commit()) { "保存资源库失败" }
        resourceFlow.value = items
    }

    fun begin(context: Context, kind: String, source: String, printer: String = "") {
        check(!statusFlow.value.active) { "已有导入正在进行，请先取消" }
        check(prefs(context).edit().putString("kind", kind).putString("source", source).putString("printer", printer)
            .putLong("started", System.currentTimeMillis()).putBoolean("active", true).commit()) { "无法启动导入" }
        statusFlow.value = ImportStatus(true, "正在打开 Bambu Handy…")
    }
    fun active(context: Context): Boolean = prefs(context).getBoolean("active", false)
    fun kind(context: Context): String = prefs(context).getString("kind", "PRINTER").orEmpty()
    fun source(context: Context): String = prefs(context).getString("source", "设备").orEmpty()
    fun printer(context: Context): String = prefs(context).getString("printer", "").orEmpty()
    fun started(context: Context): Long = prefs(context).getLong("started", 0)
    fun progress(message: String, count: Int) { statusFlow.value = ImportStatus(true, message, count) }
    fun finish(context: Context, message: String, count: Int = statusFlow.value.count) {
        prefs(context).edit().putBoolean("active", false).apply()
        statusFlow.value = ImportStatus(false, message, count)
    }
}

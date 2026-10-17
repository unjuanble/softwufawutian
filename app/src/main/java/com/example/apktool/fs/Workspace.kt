package com.example.apktool.fs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一个工作区 = 一个根目录（真实路径或 SAF 授权目录）。 */
data class Workspace(
    val id: String,
    val name: String,
    val path: String,
    val saf: Boolean = false,
)

/**
 * 工作区仓库（抽屉「工作区」分组的来源）。
 *
 * 只存本机 SharedPreferences("workspaces")：`items` 是工作区列表，`current` 是当前工作区 id。
 * 默认给两个人人都会用的目录，用户可在侧边栏「＋ 添加工作区」或设置页增删。
 */
object Workspaces {

    private const val PREFS = "workspaces"
    private const val KEY_ITEMS = "items"
    private const val KEY_CURRENT = "current"

    val items = mutableStateListOf<Workspace>()

    var currentId by mutableStateOf("")

    private val DEFAULTS = listOf(
        Workspace("w-download", "Download", "/sdcard/Download"),
        Workspace("w-documents", "Documents", "/sdcard/Documents"),
    )

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_ITEMS, null)
        items.clear()
        if (raw.isNullOrBlank()) {
            items.addAll(DEFAULTS)
            save(ctx)
        } else {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("path", "").trim()
                    if (path.isEmpty()) continue
                    items.add(
                        Workspace(
                            id = o.optString("id", "w$i"),
                            name = o.optString("name", path.substringAfterLast('/')),
                            path = path,
                            saf = o.optBoolean("saf", false),
                        )
                    )
                }
            }
            if (items.isEmpty()) items.addAll(DEFAULTS)
        }
        currentId = sp.getString(KEY_CURRENT, "") ?: ""
        if (currentOrNull() == null) currentId = items.firstOrNull()?.id ?: ""
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        items.forEach { w ->
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("name", w.name)
                    .put("path", w.path)
                    .put("saf", w.saf)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, arr.toString())
            .putString(KEY_CURRENT, currentId)
            .apply()
    }

    fun currentOrNull(): Workspace? = items.firstOrNull { it.id == currentId }

    fun setCurrent(ctx: Context, id: String) {
        currentId = id
        save(ctx)
    }

    /** 追加一个工作区；路径已存在则直接返回已有的那个。 */
    fun add(ctx: Context, path: String, name: String? = null, saf: Boolean = false): Workspace? {
        val clean = Fs.normalize(path)
        if (clean.isBlank() || clean == "/") return null
        items.firstOrNull { it.path == clean }?.let {
            setCurrent(ctx, it.id)
            return it
        }
        val w = Workspace(
            id = "w" + System.currentTimeMillis().toString(36),
            name = (name ?: clean.substringAfterLast('/')).ifBlank { clean },
            path = clean,
            saf = saf,
        )
        items.add(w)
        currentId = w.id
        save(ctx)
        return w
    }

    fun remove(ctx: Context, id: String) {
        items.removeAll { it.id == id }
        if (currentId == id) currentId = items.firstOrNull()?.id ?: ""
        save(ctx)
    }

    fun rename(ctx: Context, id: String, name: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = items[i].copy(name = name.trim().ifBlank { items[i].path.substringAfterLast('/') })
        save(ctx)
    }

    /** 目录里最新的几个文件（抽屉里展开工作区时显示的「最近文件」）。 */
    fun recentFiles(w: Workspace, limit: Int = 5): List<File> = runCatching {
        val dir = File(w.path)
        if (!dir.isDirectory) emptyList()
        else dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(limit)
            ?: emptyList()
    }.getOrDefault(emptyList())
}

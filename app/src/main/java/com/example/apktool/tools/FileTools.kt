package com.example.apktool.tools

import com.example.apktool.fs.Fs
import com.example.apktool.fs.FsException
import com.example.apktool.mcp.McpTool
import com.example.apktool.mcp.S
import com.example.apktool.mcp.ToolRegistry
import com.example.apktool.mcp.ToolResult
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** 文件管理工具集：真实路径优先、SAF 自动回退。 */
object FileTools {

    private fun nodeJson(n: com.example.apktool.fs.FsNode): JSONObject = JSONObject()
        .put("name", n.name)
        .put("path", n.path)
        .put("dir", n.isDir)
        .put("size", n.size)
        .put("mtime", n.lastModified)
        .put("via", n.via)

    private fun requireString(args: JSONObject, key: String): String {
        val v = args.optString(key, "")
        if (v.isBlank()) throw FsException("缺少参数 $key")
        return v
    }

    fun register(reg: ToolRegistry, ctx: Context) {
        reg.register(
            McpTool(
                "storage_status",
                "报告存储访问模式：是否已授予「所有文件访问权限」、已授权哪些 SAF 目录、App 自己的目录在哪。" +
                    "其他 file_* 工具在无 All files access 时只能访问这些目录。",
                S.obj(),
            ) {
                val roots = JSONArray()
                Fs.savedRoots(ctx).forEach { r -> roots.put(JSONObject().put("tree", r.uri.toString()).put("path", r.realPath)) }
                val known = JSONArray()
                Fs.knownRoots(ctx).forEach { known.put(it) }
                ToolResult.json(
                    JSONObject()
                        .put("all_files_access", Fs.hasAllFiles(ctx))
                        .put("access_mode", if (Fs.hasAllFiles(ctx)) "real-path" else "saf-fallback")
                        .put("saf_roots", roots)
                        .put("candidate_roots", known)
                        .put("app_external_dir", ctx.getExternalFilesDir(null)?.absolutePath ?: "")
                        .put("app_files_dir", ctx.filesDir.absolutePath)
                        .put("hint", if (Fs.hasAllFiles(ctx)) "可以访问 /sdcard 等真实路径。" else "想访问 /sdcard 请在 App 界面授予「所有文件访问权限」，或用 SAF 授权某个目录。")
                )
            }
        )

        reg.register(
            McpTool(
                "file_list",
                "列目录。返回 name/path/dir/size/mtime/via（via=real 表示真实路径，via=saf 表示走 SAF 回退）。",
                S.obj(
                    mapOf(
                        "path" to S.str("要列出的目录绝对路径，例如 /sdcard/Download"),
                        "showHidden" to S.bool("是否包含以 . 开头的隐藏项，默认 false"),
                        "limit" to S.int("最多返回多少项，默认 500", default = 500, min = 1, max = 5000),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val showHidden = args.optBoolean("showHidden", false)
                val limit = args.optInt("limit", 500)
                val entries = Fs.list(ctx, path)
                val shown = entries.filter { showHidden || !it.name.startsWith(".") }
                val arr = JSONArray()
                shown.take(limit).forEach { arr.put(nodeJson(it)) }
                ToolResult.json(
                    JSONObject()
                        .put("path", Fs.normalize(path))
                        .put("total", shown.size)
                        .put("returned", minOf(shown.size, limit))
                        .put("dirs", shown.count { it.isDir })
                        .put("files", shown.count { !it.isDir })
                        .put("entries", arr)
                )
            }
        )

        reg.register(
            McpTool(
                "file_stat",
                "查看单个文件/目录的元数据；hash=true 时顺带算 SHA-256（大文件会慢）。",
                S.obj(
                    mapOf(
                        "path" to S.str("目标绝对路径"),
                        "hash" to S.bool("是否计算 SHA-256，默认 false"),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val n = Fs.stat(ctx, path)
                val o = nodeJson(n)
                if (args.optBoolean("hash", false) && !n.isDir) o.put("sha256", Fs.hash(ctx, path))
                ToolResult.json(o)
            }
        )

        reg.register(
            McpTool(
                "file_read_text",
                "读文本文件（UTF-8，非法字节按替换字符处理）。默认最多 256 KiB。",
                S.obj(
                    mapOf(
                        "path" to S.str("文件绝对路径"),
                        "maxBytes" to S.int("最多读多少字节，默认 262144", default = 262144, min = 1, max = 8 shl 20),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val max = args.optInt("maxBytes", 262144)
                val n = Fs.stat(ctx, path)
                if (n.isDir) throw FsException("$path 是目录")
                val bytes = Fs.readRange(ctx, path, 0, minOf(n.size, max.toLong()).toInt())
                val text = String(bytes, Charsets.UTF_8)
                ToolResult.json(
                    JSONObject()
                        .put("path", Fs.normalize(path))
                        .put("size", n.size)
                        .put("readBytes", bytes.size)
                        .put("truncated", n.size > bytes.size)
                        .put("text", text)
                )
            }
        )

        reg.register(
            McpTool(
                "file_read_bytes",
                "按偏移读一段原始字节，返回 base64（用于看文件头/魔数）。",
                S.obj(
                    mapOf(
                        "path" to S.str("文件绝对路径"),
                        "offset" to S.int("起始偏移，默认 0", default = 0, min = 0),
                        "length" to S.int("读多少字节，默认 256，最多 1 MiB", default = 256, min = 1, max = 1 shl 20),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val offset = args.optInt("offset", 0).toLong()
                val length = args.optInt("length", 256)
                val bytes = Fs.readRange(ctx, path, offset, length)
                ToolResult.json(
                    JSONObject()
                        .put("path", Fs.normalize(path))
                        .put("offset", offset)
                        .put("length", bytes.size)
                        .put("base64", Base64.getEncoder().encodeToString(bytes))
                        .put("hex", bytes.take(64).joinToString(" ") { "%02x".format(it) })
                )
            }
        )

        reg.register(
            McpTool(
                "file_search",
                "按名字递归搜索目录（大小写不敏感的子串匹配）。",
                S.obj(
                    mapOf(
                        "path" to S.str("起始目录"),
                        "nameContains" to S.str("文件名里要包含的文本"),
                        "maxDepth" to S.int("最大递归深度，默认 4", default = 4, min = 1, max = 12),
                        "limit" to S.int("最多返回条数，默认 200", default = 200, min = 1, max = 2000),
                    ),
                    required = listOf("path", "nameContains"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val needle = requireString(args, "nameContains").lowercase()
                val maxDepth = args.optInt("maxDepth", 4)
                val limit = args.optInt("limit", 200)
                val hits = ArrayList<com.example.apktool.fs.FsNode>()
                val errors = ArrayList<String>()

                fun walk(dir: String, depth: Int) {
                    if (depth > maxDepth || hits.size >= limit) return
                    val kids = try {
                        Fs.list(ctx, dir)
                    } catch (e: Throwable) {
                        errors += "$dir: ${e.message}"
                        return
                    }
                    for (k in kids) {
                        if (hits.size >= limit) return
                        if (k.name.lowercase().contains(needle)) hits += k
                        if (k.isDir) walk(k.path, depth + 1)
                    }
                }
                walk(path, 1)
                val arr = JSONArray()
                hits.forEach { arr.put(nodeJson(it)) }
                ToolResult.json(
                    JSONObject()
                        .put("root", Fs.normalize(path))
                        .put("matches", hits.size)
                        .put("limitReached", hits.size >= limit)
                        .put("results", arr)
                        .put("errors", JSONArray(errors.take(20)))
                )
            }
        )

        reg.register(
            McpTool(
                "file_write_text",
                "写文本文件（UTF-8）。父目录不存在会自动创建；append=true 表示追加。",
                S.obj(
                    mapOf(
                        "path" to S.str("目标绝对路径"),
                        "text" to S.str("要写入的内容"),
                        "append" to S.bool("是否追加，默认 false（覆盖）", default = false),
                    ),
                    required = listOf("path", "text"),
                ),
                destructive = true,
            ) { args ->
                val path = requireString(args, "path")
                val text = args.optString("text", "")
                val append = args.optBoolean("append", false)
                Fs.writeBytes(ctx, path, text.toByteArray(Charsets.UTF_8), append)
                val n = Fs.stat(ctx, path)
                ToolResult.json(JSONObject().put("ok", true).put("path", n.path).put("size", n.size).put("via", n.via))
            }
        )

        reg.register(
            McpTool(
                "file_mkdir",
                "创建目录（含多级父目录）。",
                S.obj(mapOf("path" to S.str("要创建的目录绝对路径")), required = listOf("path")),
            ) { args ->
                val path = requireString(args, "path")
                Fs.mkdir(ctx, path)
                ToolResult.json(JSONObject().put("ok", true).put("path", Fs.normalize(path)))
            }
        )

        reg.register(
            McpTool(
                "file_delete",
                "删除文件或目录。删非空目录必须显式 recursive=true。",
                S.obj(
                    mapOf(
                        "path" to S.str("要删除的绝对路径"),
                        "recursive" to S.bool("是否递归删除目录，默认 false", default = false),
                    ),
                    required = listOf("path"),
                ),
                destructive = true,
            ) { args ->
                val path = requireString(args, "path")
                Fs.delete(ctx, path, args.optBoolean("recursive", false))
                ToolResult.json(JSONObject().put("ok", true).put("deleted", Fs.normalize(path)))
            }
        )

        reg.register(
            McpTool(
                "file_copy",
                "复制文件（跨真实路径/SAF 目录均可）。",
                S.obj(
                    mapOf("from" to S.str("源路径"), "to" to S.str("目标路径")),
                    required = listOf("from", "to"),
                ),
                destructive = true,
            ) { args ->
                val from = requireString(args, "from")
                val to = requireString(args, "to")
                Fs.copy(ctx, from, to)
                ToolResult.json(JSONObject().put("ok", true).put("from", Fs.normalize(from)).put("to", Fs.normalize(to)).put("size", Fs.stat(ctx, to).size))
            }
        )

        reg.register(
            McpTool(
                "file_move",
                "移动/改名（同一文件系统内用 rename，否则复制后删除）。",
                S.obj(
                    mapOf("from" to S.str("源路径"), "to" to S.str("目标路径")),
                    required = listOf("from", "to"),
                ),
                destructive = true,
            ) { args ->
                val from = requireString(args, "from")
                val to = requireString(args, "to")
                Fs.move(ctx, from, to)
                ToolResult.json(JSONObject().put("ok", true).put("from", Fs.normalize(from)).put("to", Fs.normalize(to)))
            }
        )
    }
}

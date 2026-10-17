package com.example.apktool.tools

import android.content.Context
import com.example.apktool.fs.Fs
import com.example.apktool.fs.FsException
import com.example.apktool.mcp.McpTool
import com.example.apktool.mcp.S
import com.example.apktool.mcp.ToolRegistry
import com.example.apktool.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * 文本/代码编辑工具：搜索（带行号）＋ 正则替换（带 diff，可先预览）。
 *
 * 写入是「先写同目录临时文件，再 rename 覆盖」，避免中途失败把原文件写坏。
 */
object TextTools {

    private const val MAX_READ = 8L * 1024 * 1024

    private fun requireString(args: JSONObject, key: String): String {
        val v = args.optString(key, "")
        if (v.isBlank()) throw FsException("缺少参数 $key")
        return v
    }

    private fun readText(ctx: Context, path: String): String =
        Fs.readAll(ctx, path, MAX_READ).toString(Charsets.UTF_8)

    /** 行号 → 文本，用于 diff 上下文。 */
    private fun lines(s: String): List<String> = s.split("\n")

    /** 生成简短 diff：只显示改动行与前后 2 行上下文。 */
    private fun diff(old: List<String>, new: List<String>, maxHunks: Int = 40): String {
        val sb = StringBuilder()
        var i = 0
        var j = 0
        var hunks = 0
        while (i < old.size || j < new.size) {
            if (i < old.size && j < new.size && old[i] == new[j]) {
                i++; j++; continue
            }
            var oi = i
            var nj = j
            while (oi < old.size && nj < new.size && old[oi] != new[nj]) {
                oi++; nj++
            }
            // 找下一处相同点
            while (oi < old.size && nj < new.size && old[oi] != new[nj]) {
                oi++; nj++
            }
            if (hunks++ >= maxHunks) {
                sb.append("…（还有更多改动，已省略）\n")
                break
            }
            val ctxFrom = maxOf(0, i - 2)
            sb.append("@@ 行 ${i + 1} @@\n")
            for (k in ctxFrom until i) sb.append("  ${old[k]}\n")
            for (k in i until oi) sb.append("- ${old[k]}\n")
            for (k in j until nj) sb.append("+ ${new[k]}\n")
            i = oi
            j = nj
        }
        return if (sb.isEmpty()) "（无变化）" else sb.toString()
    }

    fun register(reg: ToolRegistry, ctx: Context) {
        reg.register(
            McpTool(
                "text_grep",
                "在文本文件里按字符串或正则搜索，返回命中的行号与该行内容（最多 maxMatches 条）。适合先定位再改。",
                S.obj(
                    mapOf(
                        "path" to S.str("要搜索的文件绝对路径"),
                        "pattern" to S.str("要查找的字符串；regex=true 时按 Java 正则解释"),
                        "regex" to S.bool("是否按正则解释 pattern，默认 false"),
                        "ignoreCase" to S.bool("忽略大小写，默认 false"),
                        "maxMatches" to S.int("最多返回多少条命中，默认 100", default = 100, min = 1, max = 2000),
                    ),
                    required = listOf("path", "pattern"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val pattern = requireString(args, "pattern")
                val regex = args.optBoolean("regex", false)
                val ic = args.optBoolean("ignoreCase", false)
                val max = args.optInt("maxMatches", 100)
                if (Fs.stat(ctx, path).isDir) throw FsException("这是目录：$path")

                val text = readText(ctx, path)
                val out = JSONArray()
                var total = 0
                if (regex) {
                    val flags = (if (ic) Pattern.CASE_INSENSITIVE else 0) or Pattern.MULTILINE
                    val p = Pattern.compile(pattern, flags)
                    lines(text).forEachIndexed { idx, line ->
                        val m = p.matcher(line)
                        while (m.find()) {
                            total++
                            if (out.length() < max) {
                                out.put(
                                    JSONObject().put("line", idx + 1).put("text", line)
                                        .put("match", m.group())
                                )
                            }
                        }
                    }
                } else {
                    val needle = if (ic) pattern.lowercase() else pattern
                    lines(text).forEachIndexed { idx, line ->
                        val hay = if (ic) line.lowercase() else line
                        var at = hay.indexOf(needle)
                        while (at >= 0) {
                            total++
                            if (out.length() < max) {
                                out.put(
                                    JSONObject().put("line", idx + 1).put("text", line)
                                        .put("column", at + 1)
                                )
                            }
                            at = hay.indexOf(needle, at + needle.length)
                        }
                    }
                }
                ToolResult.json(
                    JSONObject()
                        .put("path", Fs.normalize(path))
                        .put("lines", lines(text).size)
                        .put("bytes", Fs.stat(ctx, path).size)
                        .put("totalMatches", total)
                        .put("returned", out.length())
                        .put("matches", out)
                )
            }
        )

        reg.register(
            McpTool(
                "text_replace",
                "在文本文件里替换字符串或正则。返回替换前后 diff。preview=true 只预览不写盘；" +
                    "写盘时先写临时文件再原子替换，且会返回 replacements（实际替换处数）。",
                S.obj(
                    mapOf(
                        "path" to S.str("文件绝对路径"),
                        "find" to S.str("要被替换的内容；regex=true 时是 Java 正则（可用 $1 反向引用）"),
                        "replace" to S.str("替换成什么（可为空串，表示删除）"),
                        "regex" to S.bool("find 是否按正则解释，默认 false"),
                        "ignoreCase" to S.bool("忽略大小写，默认 false"),
                        "all" to S.bool("是否替换全部，默认 true；false 只替换第一处"),
                        "preview" to S.bool("true=只预览不写盘，默认 false"),
                    ),
                    required = listOf("path", "find", "replace"),
                ),
                destructive = true,
            ) { args ->
                val path = requireString(args, "path")
                val find = args.optString("find", "")
                val repl = args.optString("replace", "")
                val regex = args.optBoolean("regex", false)
                val ic = args.optBoolean("ignoreCase", false)
                val all = args.optBoolean("all", true)
                val preview = args.optBoolean("preview", false)
                if (find.isEmpty()) throw FsException("find 不能为空（要删除整行请用正则）")

                val before = readText(ctx, path)
                val after: String
                val count: Int
                if (regex) {
                    val flags = (if (ic) Pattern.CASE_INSENSITIVE else 0) or Pattern.MULTILINE
                    val p = Pattern.compile(find, flags)
                    val m = p.matcher(before)
                    count = if (all) {
                        var c = 0
                        val sb = StringBuffer()
                        while (m.find()) { c++; m.appendReplacement(sb, repl) }
                        m.appendTail(sb)
                        after = sb.toString()
                        c
                    } else {
                        if (m.find()) {
                            after = m.replaceFirst(repl)
                            1
                        } else {
                            after = before
                            0
                        }
                    }
                } else {
                    if (all) {
                        var c = 0
                        var from = 0
                        val sb = StringBuilder()
                        val hay = if (ic) before.lowercase() else before
                        val needle = if (ic) find.lowercase() else find
                        var at = hay.indexOf(needle)
                        while (at >= 0) {
                            sb.append(before, from, at).append(repl)
                            from = at + needle.length
                            c++
                            at = hay.indexOf(needle, from)
                        }
                        sb.append(before, from, before.length)
                        after = sb.toString()
                        count = c
                    } else {
                        val at = if (ic) before.lowercase().indexOf(find.lowercase()) else before.indexOf(find)
                        if (at >= 0) {
                            after = before.substring(0, at) + repl + before.substring(at + find.length)
                            count = 1
                        } else {
                            after = before
                            count = 0
                        }
                    }
                }

                if (count == 0) {
                    return@McpTool ToolResult.json(
                        JSONObject().put("path", Fs.normalize(path))
                            .put("replacements", 0)
                            .put("hint", "没有匹配到 find，文件未改动")
                    )
                }

                var written = false
                if (!preview && after != before) {
                    val tmp = Fs.normalize(path) + ".apktool-agent.tmp"
                    Fs.writeBytes(ctx, tmp, after.toByteArray(Charsets.UTF_8))
                    Fs.move(ctx, tmp, path)
                    written = true
                }

                ToolResult.json(
                    JSONObject()
                        .put("path", Fs.normalize(path))
                        .put("replacements", count)
                        .put("written", written)
                        .put("preview", preview)
                        .put("bytesBefore", before.toByteArray(Charsets.UTF_8).size)
                        .put("bytesAfter", after.toByteArray(Charsets.UTF_8).size)
                        .put("diff", diff(lines(before), lines(after)))
                )
            }
        )

        reg.register(
            McpTool(
                "text_stat",
                "统计文本文件的行数、字符数与结尾是否有换行；配合 text_grep 定位用。",
                S.obj(
                    mapOf("path" to S.str("文件绝对路径")),
                    required = listOf("path"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val t = readText(ctx, path)
                ToolResult.json(
                    JSONObject()
                        .put("path", Fs.normalize(path))
                        .put("lines", t.split("\n").size)
                        .put("chars", t.length)
                        .put("endsWithNewline", t.endsWith("\n"))
                        .put("crlf", t.contains("\r\n"))
                )
            }
        )
    }
}

package com.example.apktool.tools

import android.content.Context
import com.example.apktool.fs.Fs
import com.example.apktool.mcp.McpTool
import com.example.apktool.mcp.S
import com.example.apktool.mcp.ToolRegistry
import com.example.apktool.mcp.ToolResult
import com.example.apktool.apk.ApkArchive
import com.example.apktool.apk.ArscParser
import com.example.apktool.apk.AxmlDecoder
import com.example.apktool.apk.DexParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 只读 APK 解析工具集。 */
object ApkTools {

    private fun requireString(args: JSONObject, key: String): String {
        val v = args.optString(key, "")
        require(v.isNotBlank()) { "缺少参数 $key" }
        return v
    }

    /**
     * 拿到一个可随机读的 File：真实路径直接返回；只有 SAF 权限时先复制到 App 缓存再解析。
     * 返回 (file, note)。
     */
    fun resolvable(ctx: Context, path: String): Pair<File, String?> {
        val p = Fs.normalize(path)
        if (Fs.canUseReal(ctx, p)) return File(p) to null
        val dir = File(ctx.cacheDir, "apk-cache").apply { mkdirs() }
        val out = File(dir, p.trim('/').replace('/', '_'))
        Fs.openInput(ctx, p).use { ins ->
            out.outputStream().use { os -> ins.copyTo(os, 256 * 1024) }
        }
        return out to "该路径只有 SAF 权限，已先复制到 App 缓存 ${out.absolutePath} 再解析（原始文件未被修改）。"
    }

    fun register(reg: ToolRegistry, ctx: Context) {
        reg.register(
            McpTool(
                "apk_info",
                "解析 APK 的基本信息：包名/版本/SDK/权限/四大组件/签名摘要。优先用系统的 PackageManager，" +
                    "它是 Android 自己的解析器，最权威。",
                S.obj(
                    mapOf(
                        "path" to S.str("APK 文件绝对路径，例如 /sdcard/Download/app.apk"),
                        "listComponents" to S.bool("是否列出全部组件（可能很长），默认 false 只给数量", default = false),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val path = requireString(args, "path")
                val (file, note) = resolvable(ctx, path)
                val o = JSONObject()
                o.put("path", Fs.normalize(path))
                o.put("fileSize", file.length())
                o.put("fileSha256", ApkArchive.sha256(file))
                if (note != null) o.put("note", note)

                val pm = ctx.packageManager
                var flags = android.content.pm.PackageManager.GET_ACTIVITIES or
                    android.content.pm.PackageManager.GET_SERVICES or
                    android.content.pm.PackageManager.GET_RECEIVERS or
                    android.content.pm.PackageManager.GET_PROVIDERS or
                    android.content.pm.PackageManager.GET_PERMISSIONS
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    flags = flags or 0x08000000 // GET_SIGNING_CERTIFICATES
                }
                val pi = try {
                    pm.getPackageArchiveInfo(file.absolutePath, flags)
                } catch (t: Throwable) {
                    o.put("packageManagerError", "${t.javaClass.simpleName}: ${t.message}")
                    null
                }

                if (pi != null) {
                    pi.applicationInfo?.let { ai ->
                        ai.sourceDir = file.absolutePath
                        ai.publicSourceDir = file.absolutePath
                    }
                    o.put("packageName", pi.packageName ?: "")
                    o.put("versionName", pi.versionName ?: "")
                    o.put("versionCode", pi.versionCode)
                    if (android.os.Build.VERSION.SDK_INT >= 28) o.put("longVersionCode", pi.longVersionCode)
                    val ai = pi.applicationInfo
                    if (ai != null) {
                        o.put("label", runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(""))
                        o.put("minSdk", ai.minSdkVersion)
                        o.put("targetSdk", ai.targetSdkVersion)
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            o.put("compileSdk", ai.compileSdkVersion)
                        }
                        o.put("enabled", ai.enabled)
                        o.put("debuggable", (ai.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                        o.put("largeHeap", (ai.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0)
                        o.put("nativeLibs", ai.nativeLibraryDir ?: "")
                    }
                    val perms = JSONArray()
                    pi.requestedPermissions?.forEachIndexed { i, perm ->
                        val granted = pi.requestedPermissionsFlags?.getOrNull(i)?.and(1) != 0
                        perms.put(JSONObject().put("name", perm).put("grantedOnThisDevice", granted))
                    }
                    o.put("permissions", perms)

                    val listComponents = args.optBoolean("listComponents", false)
                    fun pack(arr: Array<out android.content.pm.ComponentInfo>?): JSONObject {
                        val out = JSONObject()
                        out.put("count", arr?.size ?: 0)
                        if (listComponents && arr != null) {
                            val ja = JSONArray()
                            arr.forEach { c ->
                                ja.put(
                                    JSONObject()
                                        .put("name", c.name)
                                        .put("exported", c.exported)
                                        .put("processName", c.processName ?: "")
                                )
                            }
                            out.put("items", ja)
                        }
                        return out
                    }
                    o.put("activities", pack(pi.activities))
                    o.put("services", pack(pi.services))
                    o.put("receivers", pack(pi.receivers))
                    o.put("providers", pack(pi.providers))

                    val signingInfo = if (android.os.Build.VERSION.SDK_INT >= 28) pi.signingInfo else null
                    val sigs = when {
                        signingInfo != null -> {
                            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                            else signingInfo.signingCertificateHistory
                        }
                        else -> @Suppress("DEPRECATION") pi.signatures
                    }
                    val certs = JSONArray()
                    sigs?.take(8)?.forEach { s ->
                        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        certs.put(
                            JSONObject()
                                .put("sha256", sha256)
                                .put("subject", runCatching {
                                    java.security.cert.CertificateFactory.getInstance("X.509")
                                        .generateCertificate(s.toByteArray().inputStream())
                                        .let { (it as java.security.cert.X509Certificate).subjectX500Principal.name }
                                }.getOrDefault(""))
                        )
                    }
                    o.put("signingCerts", certs)
                }

                // 包体结构：dex 数与条目数
                o.put("dexEntries", JSONArray(DexParser.dexEntries(file)))
                o.put("zipEntries", ApkArchive.entries(file).size)
                ToolResult.json(o)
            }
        )

        reg.register(
            McpTool(
                "apk_entries",
                "列出 APK 内部的 ZIP 条目（可按名字子串过滤）。",
                S.obj(
                    mapOf(
                        "path" to S.str("APK 绝对路径"),
                        "filter" to S.str("名字里包含的文本（可选）"),
                        "offset" to S.int("跳过前 N 条", default = 0, min = 0),
                        "limit" to S.int("最多返回条数，默认 200", default = 200, min = 1, max = 2000),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val (file, note) = resolvable(ctx, requireString(args, "path"))
                val filter = args.optString("filter", "").lowercase()
                val offset = args.optInt("offset", 0)
                val limit = args.optInt("limit", 200)
                val all = ApkArchive.entries(file).filter { filter.isEmpty() || it.name.lowercase().contains(filter) }
                val arr = JSONArray()
                all.drop(offset).take(limit).forEach { e ->
                    arr.put(
                        JSONObject()
                            .put("name", e.name)
                            .put("dir", e.isDir)
                            .put("size", e.size)
                            .put("compressed", e.compressedSize)
                            .put("method", if (e.method == 8) "deflate" else "store")
                    )
                }
                val o = JSONObject()
                    .put("matched", all.size)
                    .put("returned", arr.length())
                    .put("entries", arr)
                if (note != null) o.put("note", note)
                ToolResult.json(o)
            }
        )

        reg.register(
            McpTool(
                "apk_manifest",
                "把 APK 里的 AndroidManifest.xml 从二进制 AXML 解码成文本。",
                S.obj(mapOf("path" to S.str("APK 绝对路径")), required = listOf("path")),
            ) { args ->
                val (file, note) = resolvable(ctx, requireString(args, "path"))
                val bytes = ApkArchive.readEntry(file, "AndroidManifest.xml")
                    ?: return@McpTool ToolResult.fail("这个 APK 里没有 AndroidManifest.xml")
                val xml = AxmlDecoder.decode(bytes)
                val o = JSONObject().put("path", Fs.normalize(args.optString("path"))).put("bytes", bytes.size).put("xml", xml)
                if (note != null) o.put("note", note)
                ToolResult.ok(xml)
            }
        )

        reg.register(
            McpTool(
                "apk_dex_classes",
                "列出 APK 内所有 dex 的类（含父类与访问标志），可按类名子串过滤。用于快速摸清一个 APK 的代码结构。",
                S.obj(
                    mapOf(
                        "path" to S.str("APK 绝对路径"),
                        "filter" to S.str("类名/父类名子串过滤（可选），例如 shizuku"),
                        "offset" to S.int("跳过前 N 个", default = 0, min = 0),
                        "limit" to S.int("最多返回多少个类，默认 100", default = 100, min = 1, max = 2000),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val (file, note) = resolvable(ctx, requireString(args, "path"))
                val (page, total, perDex) = DexParser.classList(
                    file,
                    args.optString("filter", "").ifBlank { null },
                    args.optInt("offset", 0),
                    args.optInt("limit", 100),
                )
                val arr = JSONArray()
                page.forEach { c ->
                    arr.put(
                        JSONObject()
                            .put("name", c.name)
                            .put("access", c.access)
                            .put("super", c.superClass ?: "")
                            .put("dexEntry", c.dexEntry)
                    )
                }
                val o = JSONObject()
                    .put("matched", total)
                    .put("returned", arr.length())
                    .put("dexes", JSONArray(perDex))
                    .put("classes", arr)
                if (note != null) o.put("note", note)
                ToolResult.json(o)
            }
        )

        reg.register(
            McpTool(
                "apk_dex_class",
                "看单个类的结构：访问标志、父类、接口、源文件名，以及全部字段与方法签名（不反编译方法体）。",
                S.obj(
                    mapOf(
                        "path" to S.str("APK 绝对路径"),
                        "className" to S.str("类名，点分或描述符都行，例如 bin.mt.plus.Main 或 Lbin/mt/plus/Main;"),
                        "dexIndex" to S.int("只在第几个 dex 里找（从 0 开始，可选）", min = 0),
                    ),
                    required = listOf("path", "className"),
                ),
            ) { args ->
                val (file, note) = resolvable(ctx, requireString(args, "path"))
                val raw = requireString(args, "className")
                val className = if (raw.startsWith("L") && raw.endsWith(";")) DexParser.humanize(raw) else raw.replace('/', '.')
                val dexIndex = if (args.has("dexIndex")) args.optInt("dexIndex") else null
                val d = DexParser.classDetail(file, className, dexIndex)
                    ?: return@McpTool ToolResult.fail("在 $className 里没找到类 $className")
                val fields = JSONArray()
                d.fields.forEach { f -> fields.put("${f.access} ${f.type} ${f.name}") }
                val methods = JSONArray()
                d.methods.forEach { m -> methods.put("${m.access} ${m.name}${m.descriptor}") }
                val o = JSONObject()
                    .put("name", d.name)
                    .put("dexEntry", d.dexEntry)
                    .put("access", d.access)
                    .put("super", d.superClass ?: "")
                    .put("interfaces", JSONArray(d.interfaces))
                    .put("sourceFile", d.sourceFile ?: "")
                    .put("fieldsCount", d.fieldsCount)
                    .put("methodsCount", d.methodsCount)
                    .put("fields", fields)
                    .put("methods", methods)
                    .put("truncated", d.truncated)
                if (note != null) o.put("note", note)
                ToolResult.json(o)
            }
        )

        reg.register(
            McpTool(
                "apk_resources",
                "解析 resources.arsc：不给 type 时输出「包 → 类型 → 条目数」总览；给了 type（如 string/string8/layout/color）则展开该类型条目的名字与取值。",
                S.obj(
                    mapOf(
                        "path" to S.str("APK 绝对路径"),
                        "type" to S.str("要展开的资源类型名（可选）"),
                        "offset" to S.int("展开时跳过前 N 条", default = 0, min = 0),
                        "limit" to S.int("展开时最多返回条数，默认 200", default = 200, min = 1, max = 5000),
                    ),
                    required = listOf("path"),
                ),
            ) { args ->
                val (file, note) = resolvable(ctx, requireString(args, "path"))
                val bytes = ApkArchive.readEntry(file, "resources.arsc", 256L * 1024 * 1024)
                    ?: return@McpTool ToolResult.fail("这个 APK 里没有 resources.arsc")
                val type = args.optString("type", "")
                val o = JSONObject().put("arscBytes", bytes.size)
                if (type.isBlank()) {
                    val types = JSONArray()
                    ArscParser.summary(bytes).forEach { t ->
                        types.put(
                            JSONObject()
                                .put("package", t.packageName)
                                .put("packageId", "0x%02x".format(t.packageId))
                                .put("typeId", t.typeId)
                                .put("type", t.typeName)
                                .put("entries", t.entryCount)
                                .put("configs", t.configCount)
                        )
                    }
                    o.put("types", types)
                } else {
                    val (entries, total, notes) = ArscParser.entries(
                        bytes, type, args.optInt("offset", 0), args.optInt("limit", 200)
                    )
                    val arr = JSONArray()
                    entries.forEach { e -> arr.put(JSONObject().put("id", e.id).put("name", e.name).put("value", e.value)) }
                    o.put("type", type).put("matched", total).put("returned", arr.length()).put("entries", arr)
                        .put("notes", JSONArray(notes))
                }
                if (note != null) o.put("note", note)
                ToolResult.json(o)
            }
        )

        reg.register(
            McpTool(
                "apk_signature",
                "检查 APK 签名：v1（PKCS#7 证书，含指纹）/v2/v3/SourceStamp 方案是否存在、APK Signing Block 位置与 id 列表。",
                S.obj(mapOf("path" to S.str("APK 绝对路径")), required = listOf("path")),
            ) { args ->
                val (file, note) = resolvable(ctx, requireString(args, "path"))
                val rep = ApkArchive.signature(file)
                val certs = JSONArray()
                rep.v1Certs.forEach { c ->
                    certs.put(
                        JSONObject()
                            .put("subject", c.subject)
                            .put("issuer", c.issuer)
                            .put("serial", c.serial)
                            .put("notBefore", c.notBefore)
                            .put("notAfter", c.notAfter)
                            .put("sigAlg", c.sigAlg)
                            .put("keyAlg", c.keyAlg)
                            .put("keyBits", c.keyBits)
                            .put("sha256", c.sha256)
                    )
                }
                val o = JSONObject()
                    .put("fileSha256", rep.fileSha256)
                    .put("schemes", JSONArray(rep.schemes))
                    .put("sigBlockOffset", rep.sigBlockOffset)
                    .put("sigBlockIds", JSONArray(rep.sigBlockIds))
                    .put("signerCount", rep.signerCount)
                    .put("v1Certs", certs)
                    .put("notes", JSONArray(rep.notes))
                if (note != null) o.put("note", note)
                ToolResult.json(o)
            }
        )
    }
}

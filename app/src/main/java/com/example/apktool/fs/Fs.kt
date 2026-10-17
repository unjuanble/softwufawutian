package com.example.apktool.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 一个文件/目录的元数据。via 说明这次是走真实路径还是 SAF 回退。 */
data class FsNode(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val via: String,
)

class FsException(message: String) : Exception(message)

/**
 * 文件系统门面：**真实路径优先，SAF 自动回退**。
 *
 * - 真实路径：拿到 MANAGE_EXTERNAL_STORAGE（「所有文件访问权限」）后，
 *   /sdcard、/storage/XXXX-XXXX（OTG/SD 卡）等都能直接当普通 File 用。
 * - SAF 回退：没授权时，App 只能碰自己目录；用户可在界面上用系统目录选择器
 *   授权一个目录（ACTION_OPEN_DOCUMENT_TREE，权限持久化），此后落在这个目录里的
 *   真实路径会自动被映射成 document URI 来读写。
 */
object Fs {
    private const val PREFS = "saf"
    private const val KEY_TREES = "trees"

    // ------------------------------------------------------------ 路径与权限

    /** 归一化：折叠重复斜杠、解析 . 与 ..，得到绝对路径。 */
    fun normalize(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) throw FsException("路径为空")
        val abs = if (p.startsWith("/")) p else "/$p"
        val out = ArrayDeque<String>()
        for (seg in abs.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    fun hasAllFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun allFilesSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }

    fun isSharedStorage(path: String): Boolean =
        path.startsWith("/sdcard") || path.startsWith("/storage") || path.startsWith("/mnt")

    /** 常见根目录，供界面起步与工具自检用。 */
    fun knownRoots(ctx: Context): List<String> {
        val list = mutableListOf<String>()
        list += Environment.getExternalStorageDirectory().absolutePath
        list += "/sdcard"
        File("/storage").listFiles()?.forEach { if (it.isDirectory && it.name != "emulated" && it.name != "self") list += it.absolutePath }
        ctx.getExternalFilesDir(null)?.let { list += it.absolutePath }
        list += ctx.filesDir.absolutePath
        list += "/data/local/tmp"
        return list.distinct()
    }

    private fun realReadable(path: String): Boolean {
        val f = File(path)
        return f.exists() && f.canRead()
    }

    /** 真实路径是否可用（外部共享存储必须先有 All files access）。 */
    fun canUseReal(ctx: Context, path: String): Boolean {
        if (isSharedStorage(path) && !hasAllFiles(ctx)) {
            // 自己 App 的专属目录即使没有 All files access 也能直接读写
            val own = ctx.getExternalFilesDir(null)?.absolutePath
            if (own == null || !path.startsWith("$own")) return false
        }
        return realReadable(path)
    }

    // ------------------------------------------------------------ SAF 授权目录

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** tree URI 对应的真实路径（primary / 卷 ID 两种形式）。 */
    fun realPathOfTree(treeUri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Throwable) {
            return null
        } ?: return null
        val idx = docId.indexOf(':')
        if (idx < 0) return null
        val volume = docId.substring(0, idx)
        val rel = docId.substring(idx + 1)
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (rel.isEmpty()) base else Fs.normalize("$base/$rel")
    }

    /**
     * 任意 SAF URI（tree 或单文件 document）→ 真实路径，仅外部存储提供者能映射。
     * 其它提供者（Downloads / raw: 文档）返回 null，调用方应改用流式读取。
     */
    fun realPathOfDocument(uri: Uri): String? {
        if (DocumentsContract.isTreeUri(uri)) return realPathOfTree(uri)
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Throwable) {
            return null
        } ?: return null
        if (docId.startsWith("raw:")) return null
        val idx = docId.indexOf(':')
        if (idx < 0) return null
        val volume = docId.substring(0, idx)
        val rel = docId.substring(idx + 1)
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (rel.isEmpty()) base else Fs.normalize("$base/$rel")
    }

    /** 查询 SAF 文档的显示名，失败时用回退值。 */
    fun displayNameOf(ctx: Context, uri: Uri, fallback: String = "picked.bin"): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: fallback
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val n = cur.getString(idx)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: fallback
    }

    /** content:// → App 私有缓存文件，返回缓存路径（解析只有 SAF 权限的文件时用）。 */
    fun copyToCache(ctx: Context, uri: Uri, name: String): String {
        val dir = java.io.File(ctx.cacheDir, "picked").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(80).ifBlank { "picked.bin" }
        val out = java.io.File(dir, safe)
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { os -> ins.copyTo(os, 256 * 1024) }
        } ?: throw FsException("无法读取选中的文件：$uri")
        return out.absolutePath
    }

    class SafRoot(val uri: Uri, val realPath: String)

    fun savedRoots(ctx: Context): List<SafRoot> =
        (prefs(ctx).getStringSet(KEY_TREES, emptySet()) ?: emptySet()).mapNotNull { s ->
            val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return@mapNotNull null
            val p = realPathOfTree(uri) ?: return@mapNotNull null
            SafRoot(uri, p)
        }

    fun addTree(ctx: Context, treeUri: Uri) {
        val set = (prefs(ctx).getStringSet(KEY_TREES, emptySet()) ?: emptySet()).toMutableSet()
        set += treeUri.toString()
        prefs(ctx).edit().putStringSet(KEY_TREES, set).apply()
    }

    fun removeTree(ctx: Context, treeUri: Uri) {
        val set = (prefs(ctx).getStringSet(KEY_TREES, emptySet()) ?: emptySet()).toMutableSet()
        set -= treeUri.toString()
        prefs(ctx).edit().putStringSet(KEY_TREES, set).apply()
    }

    private fun childDocUri(ctx: Context, parent: Uri, name: String): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentDocId)
        ctx.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cur.getString(0))
                }
            }
        }
        return null
    }

    /** 真实路径 → document URI（若有覆盖它的授权目录）。 */
    fun documentUriFor(ctx: Context, path: String): Uri? {
        val p = normalize(path)
        for (root in savedRoots(ctx).sortedByDescending { it.realPath.length }) {
            if (p != root.realPath && !p.startsWith(root.realPath + "/")) continue
            var uri = DocumentsContract.buildDocumentUriUsingTree(
                root.uri, DocumentsContract.getTreeDocumentId(root.uri)
            )
            val rel = p.removePrefix(root.realPath).trim('/')
            if (rel.isEmpty()) return uri
            for (seg in rel.split('/')) {
                uri = childDocUri(ctx, uri, seg) ?: return null
            }
            return uri
        }
        return null
    }

    private fun parentUriOf(ctx: Context, path: String): Pair<Uri, String>? {
        val p = normalize(path)
        val parent = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        if (parent.isEmpty() || name.isEmpty()) return null
        val parentUri = documentUriFor(ctx, parent) ?: return null
        return parentUri to name
    }

    // ------------------------------------------------------------ 读

    fun stat(ctx: Context, path: String): FsNode {
        val p = normalize(path)
        if (canUseReal(ctx, p)) {
            val f = File(p)
            return FsNode(p, f.name.ifEmpty { p }, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), "real")
        }
        val uri = documentUriFor(ctx, p) ?: throw FsException(
            "无法访问 $p：既没有「所有文件访问权限」，该路径也不在任何已授权的 SAF 目录内"
        )
        ctx.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null
        )?.use { cur ->
            if (cur.moveToNext()) {
                val name = cur.getString(0) ?: p.substringAfterLast('/')
                val mime = cur.getString(1)
                val size = if (cur.isNull(2)) 0L else cur.getLong(2)
                val mtime = if (cur.isNull(3)) 0L else cur.getLong(3)
                return FsNode(p, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size, mtime, "saf")
            }
        }
        throw FsException("SAF 也查不到条目：$p")
    }

    fun exists(ctx: Context, path: String): Boolean = try {
        stat(ctx, path); true
    } catch (_: Throwable) {
        false
    }

    fun list(ctx: Context, path: String): List<FsNode> {
        val p = normalize(path)
        if (canUseReal(ctx, p)) {
            val f = File(p)
            if (!f.isDirectory) throw FsException("不是目录：$p")
            val out = ArrayList<FsNode>()
            f.listFiles()?.forEach { c ->
                out += FsNode(
                    "${p.trimEnd('/')}/${c.name}", c.name, c.isDirectory,
                    if (c.isDirectory) 0 else c.length(), c.lastModified(), "real"
                )
            }
            return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }
        val uri = documentUriFor(ctx, p) ?: throw FsException(
            "无法列出 $p：既没有「所有文件访问权限」，该路径也不在任何已授权的 SAF 目录内"
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        val out = ArrayList<FsNode>()
        ctx.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val mime = cur.getString(1)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (cur.isNull(2)) 0L else cur.getLong(2)
                val mtime = if (cur.isNull(3)) 0L else cur.getLong(3)
                out += FsNode("${p.trimEnd('/')}/$name", name, isDir, size, mtime, "saf")
            }
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun openInput(ctx: Context, path: String): InputStream {
        val p = normalize(path)
        if (canUseReal(ctx, p)) return File(p).inputStream()
        val uri = documentUriFor(ctx, p) ?: throw FsException("无法打开 $p（无权限且不在已授权 SAF 目录内）")
        return ctx.contentResolver.openInputStream(uri) ?: throw FsException("SAF 打开失败：$p")
    }

    fun readAll(ctx: Context, path: String, maxBytes: Long = 32L * 1024 * 1024): ByteArray {
        val p = normalize(path)
        if (canUseReal(ctx, p)) {
            val f = File(p)
            if (!f.isFile) throw FsException("不是普通文件：$p")
            if (f.length() > maxBytes) throw FsException("文件 ${f.length()} 字节超过上限 $maxBytes，请用 apk_* 或分段读")
            return f.readBytes()
        }
        openInput(ctx, p).use { ins ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw FsException("文件超过上限 $maxBytes 字节")
                buf.write(chunk, 0, n)
            }
            return buf.toByteArray()
        }
    }

    /** 只读一段字节（真实路径用 RandomAccessFile；SAF 只能顺序跳过）。 */
    fun readRange(ctx: Context, path: String, offset: Long, length: Int): ByteArray {
        val p = normalize(path)
        if (offset < 0 || length < 0) throw FsException("offset/length 不能为负")
        if (canUseReal(ctx, p)) {
            java.io.RandomAccessFile(File(p), "r").use { raf ->
                if (offset > raf.length()) throw FsException("offset 超出文件长度")
                raf.seek(offset)
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = raf.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                return if (read == length) buf else buf.copyOf(read)
            }
        }
        openInput(ctx, p).use { ins ->
            var skipped = 0L
            while (skipped < offset) {
                val s = ins.skip(offset - skipped)
                if (s <= 0) {
                    if (ins.read() < 0) throw FsException("offset 超出文件长度")
                    skipped += 1
                } else skipped += s
            }
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = ins.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            return if (read == length) buf else buf.copyOf(read)
        }
    }

    // ------------------------------------------------------------ 写

    /** 写权限判断：文件已存在就看它本身，否则看父目录是否可写（新文件还谈不上可读）。 */
    private fun canWriteReal(ctx: Context, path: String): Boolean {
        val f = File(path)
        if (f.exists()) return canUseReal(ctx, path)
        if (isSharedStorage(path) && !hasAllFiles(ctx)) {
            val own = ctx.getExternalFilesDir(null)?.absolutePath
            if (own == null || !path.startsWith("$own")) return false
        }
        val parent = f.parentFile ?: return false
        return parent.isDirectory && parent.canWrite()
    }

    private fun openOutput(ctx: Context, path: String, append: Boolean): OutputStream {
        val p = normalize(path)
        if (canWriteReal(ctx, p)) {
            val f = File(p)
            f.parentFile?.mkdirs()
            return java.io.FileOutputStream(f, append)
        }
        var uri = documentUriFor(ctx, p)
        if (uri == null) {
            val (parentUri, name) = parentUriOf(ctx, p) ?: throw FsException("无法定位父目录：$p")
            uri = DocumentsContract.createDocument(ctx.contentResolver, parentUri, "application/octet-stream", name)
                ?: throw FsException("SAF 创建文件失败：$p")
        }
        return ctx.contentResolver.openOutputStream(uri, if (append) "wa" else "wt")
            ?: throw FsException("SAF 打开写入失败：$p")
    }

    fun writeBytes(ctx: Context, path: String, data: ByteArray, append: Boolean = false) {
        openOutput(ctx, path, append).use { it.write(data); it.flush() }
    }

    fun mkdir(ctx: Context, path: String) {
        val p = normalize(path)
        if (canWriteReal(ctx, p) || canWriteReal(ctx, p.substringBeforeLast('/', "/"))) {
            if (!File(p).mkdirs() && !File(p).isDirectory) throw FsException("创建目录失败：$p")
            return
        }
        val (parentUri, name) = parentUriOf(ctx, p) ?: throw FsException("无法定位父目录：$p")
        DocumentsContract.createDocument(ctx.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw FsException("SAF 创建目录失败：$p")
    }

    fun delete(ctx: Context, path: String, recursive: Boolean = false) {
        val p = normalize(path)
        if (canUseReal(ctx, p)) {
            val f = File(p)
            if (!f.exists()) throw FsException("不存在：$p")
            if (f.isDirectory && !recursive) {
                val kids = f.list()?.size ?: 0
                if (kids > 0) throw FsException("目录非空（$kids 项），需要 recursive=true 才删除")
            }
            if (!f.deleteRecursively()) throw FsException("删除失败：$p")
            return
        }
        val uri = documentUriFor(ctx, p) ?: throw FsException("无法删除 $p（无权限且不在已授权 SAF 目录内）")
        if (!DocumentsContract.deleteDocument(ctx.contentResolver, uri)) throw FsException("SAF 删除失败：$p")
    }

    fun copy(ctx: Context, from: String, to: String) {
        val src = normalize(from)
        val dst = normalize(to)
        openInput(ctx, src).use { ins ->
            openOutput(ctx, dst, false).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }
    }

    fun move(ctx: Context, from: String, to: String) {
        val src = normalize(from)
        val dst = normalize(to)
        if (canUseReal(ctx, src)) {
            val f = File(src)
            val t = File(dst)
            t.parentFile?.mkdirs()
            if (f.renameTo(t)) return
        }
        copy(ctx, src, dst)
        delete(ctx, src, recursive = true)
    }

    fun hash(ctx: Context, path: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        openInput(ctx, path).use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

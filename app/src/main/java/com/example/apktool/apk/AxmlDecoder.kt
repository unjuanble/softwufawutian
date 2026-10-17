package com.example.apktool.apk

/**
 * Android 二进制 XML（AXML）解码器：把 AndroidManifest.xml / 布局资源还原成可读 XML 文本。
 *
 * 已知限制（会在输出里注明）：framework 属性（android:xxx）在很多 APK 里只有资源 id 没有名字，
 * 本解码器内置了一张常见 id 表；表外的会原样输出成 android:attr/0x0101xxxx。
 */
object AxmlDecoder {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_START_NAMESPACE = 0x0100
    private const val RES_XML_END_NAMESPACE = 0x0101
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val RES_XML_CDATA = 0x0104
    private const val RES_XML_RESOURCE_MAP = 0x0180

    /** 常见 framework 属性 id → 名字（只收「确定」的，避免编造）。 */
    private val ANDROID_ATTRS = mapOf(
        0x01010000 to "theme",
        0x01010001 to "label",
        0x01010002 to "icon",
        0x01010003 to "name",
        0x01010004 to "manageSpaceActivity",
        0x01010005 to "allowClearUserData",
        0x01010006 to "permission",
        0x01010007 to "readPermission",
        0x01010008 to "writePermission",
        0x0101000e to "enabled",
        0x0101000f to "debuggable",
        0x01010010 to "exported",
        0x01010011 to "process",
        0x01010018 to "authorities",
        0x0101001d to "description",
        0x0101020c to "minSdkVersion",
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x01010270 to "targetSdkVersion",
        0x01010280 to "allowBackup",
        0x010102b2 to "largeHeap",
        0x010103f6 to "supportsRtl",
        0x010104ea to "extractNativeLibs",
        0x01010572 to "compileSdkVersion",
        0x01010573 to "compileSdkVersionCodename",
        0x0101b3e0 to "usesCleartextTraffic",
        0x01010527 to "requestLegacyExternalStorage",
    )

    private class Cursor(val buf: ByteArray) {
        var pos = 0
        fun u8(o: Int) = buf[o].toInt() and 0xff
        fun u16(o: Int) = (u8(o)) or (u8(o + 1) shl 8)
        fun u32(o: Int): Int = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)
    }

    fun decode(buf: ByteArray): String {
        val c = Cursor(buf)
        if (buf.size < 8) return "<!-- 文件太小，不是 AXML -->"
        val rootType = c.u16(0)
        if (rootType != 0x0003) {
            return "<!-- 不是二进制 XML（chunk type=0x%04x）；可能是纯文本 XML -->".format(rootType)
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")

        var strings: List<String> = emptyList()
        var resourceMap = IntArray(0)
        val nsPrefixes = HashMap<String, String>() // uri → prefix

        var off = c.u16(2).coerceAtLeast(8)
        while (off + 8 <= buf.size) {
            val type = c.u16(off)
            val headerSize = c.u16(off + 2)
            val size = c.u32(off + 4)
            if (size <= 0 || off + size > buf.size || headerSize < 8) break
            when (type) {
                RES_STRING_POOL -> strings = parseStringPool(buf, off, headerSize)

                RES_XML_RESOURCE_MAP -> {
                    val n = (size - headerSize) / 4
                    resourceMap = IntArray(n) { c.u32(off + headerSize + it * 4) }
                }

                RES_XML_START_NAMESPACE, RES_XML_END_NAMESPACE -> {
                    val prefix = strings.getOrElse(c.u32(off + headerSize)) { "" }
                    val uri = strings.getOrElse(c.u32(off + headerSize + 4)) { "" }
                    if (type == RES_XML_START_NAMESPACE) nsPrefixes[uri] = prefix
                }

                RES_XML_START_ELEMENT -> {
                    val ext = off + headerSize
                    val nsIdx = c.u32(ext)
                    val nameIdx = c.u32(ext + 4)
                    val attrStart = c.u16(ext + 8)
                    val attrSize = c.u16(ext + 10)
                    val attrCount = c.u16(ext + 12)
                    val tag = strings.getOrElse(nameIdx) { "?" }
                    val nsPrefix = if (nsIdx == 0xFFFFFFFF.toInt()) "" else nsPrefixes[strings.getOrElse(nsIdx) { "" }] ?: ""
                    sb.append("<").append(if (nsPrefix.isNullOrEmpty()) tag else "$nsPrefix:$tag")
                    val attrBase = ext + attrStart
                    for (i in 0 until attrCount) {
                        val a = attrBase + i * attrSize
                        if (a + 20 > buf.size) break
                        val aNs = c.u32(a)
                        val aNameIdx = c.u32(a + 4)
                        val rawIdx = c.u32(a + 8)
                        var value = strings.getOrNull(rawIdx)
                        if (value.isNullOrEmpty()) value = renderValue(c, a + 12, strings)
                        var name = strings.getOrNull(aNameIdx).orEmpty()
                        if (name.isEmpty() && aNameIdx in resourceMap.indices) {
                            // framework 属性的名字不在字符串池里：用 resource map（字符串索引 → 资源 id）反查
                            val id = resourceMap[aNameIdx]
                            name = ANDROID_ATTRS[id] ?: "attr/0x%08x".format(id)
                        }
                        val prefix = when {
                            aNs == 0xFFFFFFFF.toInt() -> ""
                            nsPrefixes[strings.getOrElse(aNs) { "" }] != null ->
                                nsPrefixes[strings.getOrElse(aNs) { "" }] + ":"
                            else -> ""
                        }
                        sb.append(' ').append(prefix).append(name)
                            .append("=\"").append(escape(value)).append('"')
                    }
                    sb.append(">\n")
                }

                RES_XML_END_ELEMENT -> {
                    val nameIdx = c.u32(off + headerSize + 4)
                    val tag = strings.getOrElse(nameIdx) { "?" }
                    sb.append("</").append(tag).append(">\n")
                }

                RES_XML_CDATA -> {
                    val idx = c.u32(off + headerSize)
                    sb.append(escape(strings.getOrElse(idx) { "" })).append('\n')
                }
            }
            off += size
        }
        return sb.toString()
    }

    private fun parseStringPool(buf: ByteArray, chunkStart: Int, headerSize: Int): List<String> {
        val c = Cursor(buf)
        val count = c.u32(chunkStart + 8)
        val flags = c.u32(chunkStart + 16)
        val stringsStart = c.u32(chunkStart + 20)
        val utf8 = flags and 0x100 != 0
        val base = chunkStart + stringsStart
        val offsets = IntArray(count.coerceAtMost(200_000)) { c.u32(chunkStart + headerSize + it * 4) }
        val out = ArrayList<String>(offsets.size)
        for (o in offsets) {
            val p = base + o
            if (p < 0 || p >= buf.size) {
                out += ""
                continue
            }
            out += if (utf8) readUtf8(buf, p) else readUtf16(buf, p)
        }
        return out
    }

    private fun readUtf8(buf: ByteArray, p0: Int): String {
        var p = p0
        // uleb128：字符数
        var shift = 0
        while (p < buf.size) {
            val b = buf[p].toInt() and 0xff
            p++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) break
        }
        // uleb128：字节数
        shift = 0
        var len = 0
        while (p < buf.size) {
            val b = buf[p].toInt() and 0xff
            p++
            len = len or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) break
        }
        val end = (p + len).coerceAtMost(buf.size)
        return String(buf, p, end - p, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteArray, p0: Int): String {
        val c = Cursor(buf)
        var p = p0
        var len = c.u16(p)
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7fff) shl 16) or c.u16(p)
            p += 2
        }
        val chars = CharArray(len.coerceAtMost(1 shl 20))
        for (i in chars.indices) {
            if (p + 2 > buf.size) break
            chars[i] = c.u16(p).toChar()
            p += 2
        }
        return String(chars)
    }

    private fun renderValue(c: Cursor, off: Int, strings: List<String>): String {
        val dataType = c.u8(off + 3)
        val data = c.u32(off + 4)
        return when (dataType) {
            0x00 -> "null"
            0x01 -> "@0x%08x".format(data)
            0x02 -> "?0x%08x".format(data)
            0x03 -> strings.getOrElse(data) { "" }
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x10 -> data.toString()
            0x11 -> "0x%x".format(data)
            0x12 -> if (data != 0) "true" else "false"
            0x1c, 0x1d, 0x1e, 0x1f -> "#%08x".format(data)
            else -> "0x%08x(type=0x%02x)".format(data, dataType)
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

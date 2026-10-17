package com.example.apktool.apk

data class ArscType(
    val packageName: String,
    val packageId: Int,
    val typeId: Int,
    val typeName: String,
    val entryCount: Int,
    val configCount: Int,
)

data class ArscEntry(val id: Int, val name: String, val value: String)

/**
 * resources.arsc（编译后的资源表）只读解析：默认给出「包 → 类型 → 条目数」总览，
 * 也可以展开某个类型的条目名与取值（值从全局字符串池还原）。
 */
object ArscParser {

    private class R(val buf: ByteArray) {
        fun u8(o: Int) = if (o in buf.indices) buf[o].toInt() and 0xff else 0
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int): Int = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)
    }

    private fun stringPool(buf: ByteArray, chunkStart: Int, headerSize: Int, count: Int): List<String> {
        val r = R(buf)
        if (chunkStart + 28 > buf.size) return emptyList()
        val hs = if (headerSize >= 28) headerSize else 28
        val chunkSize = r.u32(chunkStart + 4)
        val flags = r.u32(chunkStart + 16)
        val stringsStart = r.u32(chunkStart + 20)
        val utf8 = flags and 0x100 != 0
        val n = count.coerceIn(0, 200_000)
        val base = chunkStart + stringsStart
        if (base <= 0 || base > buf.size) return emptyList()
        // 偏移表里存的是「相对 stringsStart 的字节偏移」，必须落在字符串数据区
        // [stringsStart, chunkSize) 之内 —— 早期误用 `off >= stringsStart` 判定，
        // 把后半段（menu/…/string/style/xml）整段丢掉，导致类型名表错位。
        val dataSpan = if (chunkSize > stringsStart && chunkStart + chunkSize <= buf.size) {
            chunkSize - stringsStart
        } else {
            buf.size - base
        }
        val out = ArrayList<String>(n)
        for (i in 0 until n) {
            val eo = chunkStart + hs + i * 4
            if (eo + 4 > buf.size) break
            val off = r.u32(eo)
            val p = base + off
            // 越界偏移保留一个空串占位，绝不 `continue`：否则后面的下标全部错位。
            if (off < 0 || off >= dataSpan || p < base || p >= buf.size) {
                out += ""
                continue
            }
            out += if (utf8) readUtf8(buf, p) else readUtf16(buf, p)
        }
        return out
    }

    /**
     * ARSC 的 UTF-8 字符串：先 1~2 字节的「UTF-16 字符数」，再 1~2 字节的「UTF-8 字节数」，
     * 然后是字节内容与结尾 NUL。长度前缀不是 7-bit varint，而是：
     * 首字节 < 0x80 表示 1 字节；否则 2 字节，值 = ((b0 and 0x7f) shl 8) or b1。
     * （早期版本按 7-bit varint 解析，长度会放大二十多倍，直接 OOM。）
     */
    private fun readUtf8(buf: ByteArray, p0: Int): String {
        var p = p0

        fun readLen(): Int {
            if (p >= buf.size) return -1
            val b0 = buf[p].toInt() and 0xff
            if (b0 and 0x80 == 0) {
                p++
                return b0
            }
            if (p + 1 >= buf.size) {
                p++
                return -1
            }
            val v = ((b0 and 0x7f) shl 8) or (buf[p + 1].toInt() and 0xff)
            p += 2
            return v
        }

        if (readLen() < 0) return ""      // 字符数（本函数不用，但要跳过）
        val bytes = readLen()
        if (bytes <= 0) return ""
        val end = (p + bytes).coerceAtMost(buf.size)
        if (end <= p) return ""
        return String(buf, p, end - p, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteArray, p0: Int): String {
        val r = R(buf)
        var p = p0
        if (p + 2 > buf.size) return ""
        var len = r.u16(p)
        p += 2
        if (len and 0x8000 != 0) {
            if (p + 2 > buf.size) return ""
            len = ((len and 0x7fff) shl 16) or r.u16(p)
            p += 2
        }
        // 可用字符数受缓冲区剩余长度限制：绝不按声明长度直接分配（声明值可能被损坏）。
        val avail = (buf.size - p) / 2
        len = len.coerceIn(0, avail.coerceAtLeast(0))
        if (len == 0) return ""
        val chars = CharArray(len)
        for (i in 0 until len) {
            chars[i] = r.u16(p).toChar()
            p += 2
        }
        return String(chars)
    }

    private class Package(
        val id: Int,
        val name: String,
        val typeNames: List<String>,
        val keyNames: List<String>,
        val chunkStart: Int,
        val chunkSize: Int,
    )

    private fun parse(buf: ByteArray): Pair<List<String>, List<Package>> {
        val r = R(buf)
        if (buf.size < 12 || r.u16(0) != 0x0002) throw IllegalArgumentException("不是 resources.arsc（表头类型 0x%04x）".format(r.u16(0)))
        // ResTable_header: type(2) headerSize(2) size(4) packageCount(4)。
        // 全局字符串池紧跟表头，是否存在的问题只看表头之后的第一个 chunk 类型。
        var off = r.u16(2).let { if (it >= 12) it else 12 }
        val globalPool = if (off + 8 <= buf.size && r.u16(off) == 0x0001) {
            stringPool(buf, off, r.u16(off + 2), r.u32(off + 8))
        } else emptyList()

        val pkgs = ArrayList<Package>()
        while (off + 8 <= buf.size) {
            val type = r.u16(off)
            val headerSize = r.u16(off + 2)
            val size = r.u32(off + 4)
            if (size <= 0 || off + size > buf.size) break
            if (type == 0x0200 && headerSize >= 284) {
                val id = r.u32(off + 8)
                val nameChars = CharArray(128)
                for (i in 0 until 128) nameChars[i] = r.u16(off + 12 + i * 2).toChar()
                val name = String(nameChars).trimEnd('\u0000')
                val typeStringsOff = r.u32(off + 268)
                val keyStringsOff = r.u32(off + 276)
                val typeNames = if (typeStringsOff > 0) {
                    val ts = off + typeStringsOff
                    stringPool(buf, ts, r.u16(ts + 2), r.u32(ts + 8))
                } else emptyList()
                val keyNames = if (keyStringsOff > 0) {
                    val ks = off + keyStringsOff
                    stringPool(buf, ks, r.u16(ks + 2), r.u32(ks + 8))
                } else emptyList()
                pkgs += Package(id, name, typeNames, keyNames, off, size)
            }
            off += size
        }
        return globalPool to pkgs
    }

    fun summary(buf: ByteArray): List<ArscType> {
        val (_, pkgs) = parse(buf)
        val r = R(buf)
        val out = ArrayList<ArscType>()
        for (p in pkgs) {
            val entryCounts = HashMap<Int, Int>()
            val configCounts = HashMap<Int, Int>()
            var off = p.chunkStart + r.u16(p.chunkStart + 2)
            val end = p.chunkStart + p.chunkSize
            while (off + 8 <= end && off + 8 <= buf.size) {
                val type = r.u16(off)
                val headerSize = r.u16(off + 2)
                val size = r.u32(off + 4)
                if (size <= 0 || off + size > buf.size) break
                if (type == 0x0201 && headerSize >= 20) {
                    val typeId = r.u8(off + 8)
                    val entryCount = r.u32(off + 12)
                    entryCounts[typeId] = maxOf(entryCounts[typeId] ?: 0, entryCount)
                    configCounts[typeId] = (configCounts[typeId] ?: 0) + 1
                }
                off += size
            }
            for ((typeId, count) in entryCounts) {
                out += ArscType(
                    packageName = p.name,
                    packageId = p.id,
                    typeId = typeId,
                    typeName = p.typeNames.getOrElse(typeId - 1) { "type$typeId" },
                    entryCount = count,
                    configCount = configCounts[typeId] ?: 1,
                )
            }
        }
        return out.sortedWith(compareBy({ it.packageName }, { it.typeId }))
    }

    fun entries(buf: ByteArray, typeName: String, offset: Int, limit: Int): Triple<List<ArscEntry>, Int, List<String>> {
        val (globalPool, pkgs) = parse(buf)
        val r = R(buf)
        val out = ArrayList<ArscEntry>()
        var total = 0
        val notes = ArrayList<String>()
        for (p in pkgs) {
            val typeId = p.typeNames.indexOfFirst { it.equals(typeName, ignoreCase = true) } + 1
            if (typeId <= 0) continue
            var off = p.chunkStart + r.u16(p.chunkStart + 2)
            val end = p.chunkStart + p.chunkSize
            var seen = false
            while (off + 8 <= end && off + 8 <= buf.size) {
                val type = r.u16(off)
                val headerSize = r.u16(off + 2)
                val size = r.u32(off + 4)
                if (size <= 0 || off + size > buf.size) break
                if (type == 0x0201 && headerSize >= 20 && r.u8(off + 8) == typeId) {
                    if (seen) {
                        notes += "类型 ${p.name}:$typeName 有多个 config 变体，这里只展开第一个（默认配置）。"
                    } else {
                        seen = true
                        val entryCount = r.u32(off + 12)
                        val entriesStart = r.u32(off + 16)
                        for (i in 0 until entryCount) {
                            val eo = r.u32(off + headerSize + i * 4)
                            if (eo == -1) continue
                            total += 1
                            if (total <= offset) continue
                            if (out.size >= limit) continue
                            val es = off + entriesStart + eo
                            val flags = r.u16(es + 2)
                            val keyIdx = r.u32(es + 4)
                            val name = p.keyNames.getOrElse(keyIdx) { "key$keyIdx" }
                            val value = if (flags and 0x0001 != 0) "(复杂条目：bag/map)" else renderValue(r, es + 8, globalPool)
                            out += ArscEntry(i, name, value)
                        }
                    }
                }
                off += size
            }
        }
        return Triple(out, total, notes)
    }

    private fun renderValue(r: R, off: Int, pool: List<String>): String {
        val dataType = r.u8(off + 3)
        val data = r.u32(off + 4)
        return when (dataType) {
            0x00 -> "(空)"
            0x01 -> "@ref/0x%08x".format(data)
            0x02 -> "?attr/0x%08x".format(data)
            0x03 -> pool.getOrElse(data) { "str#$data" }
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x10 -> data.toString()
            0x11 -> "0x%x".format(data)
            0x12 -> if (data != 0) "true" else "false"
            0x1c, 0x1d, 0x1e, 0x1f -> "#%08x".format(data)
            else -> "0x%08x(type=0x%02x)".format(data, dataType)
        }
    }
}

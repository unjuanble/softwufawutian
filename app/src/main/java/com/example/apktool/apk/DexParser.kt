package com.example.apktool.apk

import java.io.File

data class DexClassSummary(
    val name: String,
    val dexIndex: Int,
    val dexEntry: String,
    val access: String,
    val superClass: String?,
)

data class DexField(val access: String, val name: String, val type: String)
data class DexMethod(val access: String, val name: String, val descriptor: String, val signature: String)

data class DexClassDetail(
    val name: String,
    val dexIndex: Int,
    val dexEntry: String,
    val access: String,
    val superClass: String?,
    val interfaces: List<String>,
    val sourceFile: String?,
    val fieldsCount: Int,
    val methodsCount: Int,
    val fields: List<DexField>,
    val methods: List<DexMethod>,
    val truncated: Boolean,
)

/**
 * 纯手写的 DEX 只读解析器（不依赖 dexlib/jadx）。
 *
 * 能力：类清单（含父类与访问标志）与单类的字段/方法结构。
 * 只读、不做反编译 —— 需要源码时请把 APK 导出到电脑上用 jadx。
 * 解析器对非法索引是容忍的（返回占位名而不是抛异常），因为真实 APK 经常被改坏。
 */
object DexParser {

    private val DEX_MAGIC = "dex\n".toByteArray(Charsets.US_ASCII)

    /** 一个 APK 内部的 dex 条目，按 classes.dex / classes2.dex … 排序。 */
    fun dexEntries(apk: File): List<String> {
        val names = ApkArchive.entries(apk).map { it.name }.filter { it.matches(Regex("classes\\d*\\.dex")) }
        return names.sortedBy { n ->
            val num = n.removePrefix("classes").removeSuffix(".dex")
            if (num.isEmpty()) 1 else num.toIntOrNull() ?: Int.MAX_VALUE
        }
    }

    fun classList(
        apk: File,
        filter: String? = null,
        offset: Int = 0,
        limit: Int = 100,
    ): Triple<List<DexClassSummary>, Int, List<String>> {
        val all = ArrayList<DexClassSummary>()
        val perDex = ArrayList<String>()
        for ((i, entry) in dexEntries(apk).withIndex()) {
            val bytes = ApkArchive.readEntry(apk, entry) ?: continue
            val dex = Dex(bytes, i, entry) ?: run {
                perDex += "$entry: 不是合法 DEX（magic/大小校验失败）"
                continue
            }
            perDex += "$entry: ${dex.classCount} 个 class_def，string_ids=${dex.stringCount}"
            for (c in dex.classSummaries()) all += c
            dex.close()
        }
        val filtered = if (filter.isNullOrBlank()) all else {
            val f = filter.lowercase()
            all.filter { it.name.lowercase().contains(f) || (it.superClass?.lowercase()?.contains(f) ?: false) }
        }.sortedBy { it.name }
        val page = filtered.drop(offset).take(limit)
        return Triple(page, filtered.size, perDex)
    }

    fun classDetail(apk: File, className: String, dexIndex: Int? = null): DexClassDetail? {
        val target = className.replace('/', '.')
        for ((i, entry) in dexEntries(apk).withIndex()) {
            if (dexIndex != null && dexIndex != i) continue
            val bytes = ApkArchive.readEntry(apk, entry) ?: continue
            val dex = Dex(bytes, i, entry) ?: continue
            val idx = dex.findClass(target)
            if (idx >= 0) {
                val d = dex.classDetail(idx)
                dex.close()
                return d
            }
            dex.close()
        }
        return null
    }

    // ------------------------------------------------------------------ 内部实现

    private class Dex(val buf: ByteArray, val index: Int, val entry: String) {
        val stringCount: Int
        val classCount: Int
        private val stringOffsets: IntArray
        private val typeIdsOff: Int
        private val typeIdsSize: Int
        private val protoIdsOff: Int
        private val protoIdsSize: Int
        private val fieldIdsOff: Int
        private val fieldIdsSize: Int
        private val methodIdsOff: Int
        private val methodIdsSize: Int
        private val classDefsOff: Int

        init {
            if (buf.size < 112 || !startsWith(buf, DEX_MAGIC)) throw IllegalArgumentException("bad dex")
            val fileSize = u32(32)
            if (fileSize > buf.size) throw IllegalArgumentException("file_size ${fileSize} > 实际 ${buf.size}")
            val headerSize = u32(36)
            val endian = u32(40)
            if (headerSize != 0x70 || endian != 0x12345678) throw IllegalArgumentException("header/endian 非法")
            stringCount = u32(56)
            val stringIdsOff = u32(60)
            typeIdsSize = u32(64)
            typeIdsOff = u32(68)
            protoIdsSize = u32(72)
            protoIdsOff = u32(76)
            fieldIdsSize = u32(80)
            fieldIdsOff = u32(84)
            methodIdsSize = u32(88)
            methodIdsOff = u32(92)
            classCount = u32(96)
            classDefsOff = u32(100)

            val so = IntArray(stringCount)
            for (i in 0 until stringCount) so[i] = u32(stringIdsOff + i * 4)
            stringOffsets = so
        }

        fun close() {
            // buf 交给 GC；这里保留方法以便将来切换成 mmap
        }

        fun findClass(dotted: String): Int {
            for (i in 0 until classCount) {
                val classIdx = u32(classDefsOff + i * 32)
                if (typeName(classIdx) == dotted) return i
            }
            return -1
        }

        fun classSummaries(): List<DexClassSummary> {
            val out = ArrayList<DexClassSummary>(classCount)
            for (i in 0 until classCount) {
                val base = classDefsOff + i * 32
                val classIdx = u32(base)
                val access = u32(base + 4)
                val superIdx = u32(base + 8)
                out += DexClassSummary(
                    name = typeName(classIdx),
                    dexIndex = index,
                    dexEntry = entry,
                    access = accessFlags(access, isMethod = false),
                    superClass = if (superIdx == 0xFFFFFFFF.toInt()) null else typeName(superIdx),
                )
            }
            return out
        }

        fun classDetail(i: Int): DexClassDetail {
            val base = classDefsOff + i * 32
            val classIdx = u32(base)
            val access = u32(base + 4)
            val superIdx = u32(base + 8)
            val interfacesOff = u32(base + 12)
            val sourceFileIdx = u32(base + 16)
            val classDataOff = u32(base + 24)

            val ifaces = if (interfacesOff == 0) emptyList() else {
                val size = u32(interfacesOff)
                (0 until size).map { typeName(u16(interfacesOff + 4 + it * 2)) }
            }
            val source = if (sourceFileIdx == 0xFFFFFFFF.toInt()) null else string(sourceFileIdx)

            val staticFields = ArrayList<DexField>()
            val instanceFields = ArrayList<DexField>()
            val directMethods = ArrayList<DexMethod>()
            val virtualMethods = ArrayList<DexMethod>()

            if (classDataOff != 0) {
                var p = classDataOff
                val staticCount = readUleb(p); p = staticCount.second
                val instanceCount = readUleb(p); p = instanceCount.second
                val directCount = readUleb(p); p = directCount.second
                val virtualCount = readUleb(p); p = virtualCount.second

                var fieldIdx = 0
                repeat(staticCount.first) {
                    val diff = readUleb(p); p = diff.second
                    val acc = readUleb(p); p = acc.second
                    fieldIdx += diff.first
                    staticFields += fieldInfo(fieldIdx, acc.first)
                }
                fieldIdx = 0
                repeat(instanceCount.first) {
                    val diff = readUleb(p); p = diff.second
                    val acc = readUleb(p); p = acc.second
                    fieldIdx += diff.first
                    instanceFields += fieldInfo(fieldIdx, acc.first)
                }
                var methodIdx = 0
                repeat(directCount.first) {
                    val diff = readUleb(p); p = diff.second
                    val acc = readUleb(p); p = acc.second
                    val codeOff = readUleb(p); p = codeOff.second
                    methodIdx += diff.first
                    directMethods += methodInfo(methodIdx, acc.first)
                }
                methodIdx = 0
                repeat(virtualCount.first) {
                    val diff = readUleb(p); p = diff.second
                    val acc = readUleb(p); p = acc.second
                    val codeOff = readUleb(p); p = codeOff.second
                    methodIdx += diff.first
                    virtualMethods += methodInfo(methodIdx, acc.first)
                }
            }

            val allMethods = directMethods + virtualMethods
            val allFields = staticFields.map { it } + instanceFields
            val maxPerKind = 1500
            val truncated = allMethods.size > maxPerKind || allFields.size > maxPerKind
            return DexClassDetail(
                name = typeName(classIdx),
                dexIndex = index,
                dexEntry = entry,
                access = accessFlags(access, isMethod = false),
                superClass = if (superIdx == 0xFFFFFFFF.toInt()) null else typeName(superIdx),
                interfaces = ifaces,
                sourceFile = source,
                fieldsCount = allFields.size,
                methodsCount = allMethods.size,
                fields = allFields.take(maxPerKind),
                methods = allMethods.take(maxPerKind),
                truncated = truncated,
            )
        }

        private fun fieldInfo(idx: Int, access: Int): DexField {
            if (idx >= fieldIdsSize) return DexField(accessFlags(access, isMethod = false), "?#$idx", "?")
            val off = fieldIdsOff + idx * 8
            val classIdx = u16(off).toInt()
            val typeIdx = u16(off + 2).toInt()
            val nameIdx = u32(off + 4)
            return DexField(accessFlags(access, isMethod = false), string(nameIdx), typeName(typeIdx, classHint = classIdx))
        }

        private fun methodInfo(idx: Int, access: Int): DexMethod {
            if (idx >= methodIdsSize) return DexMethod(accessFlags(access, isMethod = true), "?#$idx", "?", "?")
            val off = methodIdsOff + idx * 8
            val protoIdx = u16(off + 2).toInt()
            val nameIdx = u32(off + 4)
            val name = string(nameIdx)
            val desc = protoDescriptor(protoIdx)
            return DexMethod(accessFlags(access, isMethod = true), name, desc, "$name$desc")
        }

        private fun protoDescriptor(protoIdx: Int): String {
            if (protoIdx >= protoIdsSize) return "(?)"
            val off = protoIdsOff + protoIdx * 12
            val returnTypeIdx = u32(off + 4)
            val paramsOff = u32(off + 8)
            val params = if (paramsOff == 0) emptyList() else {
                val size = u32(paramsOff)
                (0 until size).map { typeName(u16(paramsOff + 4 + it * 2)) }
            }
            return "(" + params.joinToString(", ") + "): " + typeName(returnTypeIdx)
        }

        // ---- 索引表访问

        fun string(idx: Int): String = string(idx.toLong())

        fun string(idx: Long): String {
            if (idx < 0 || idx >= stringCount) return "?#$idx"
            val off = stringOffsets[idx.toInt()]
            if (off <= 0 || off >= buf.size) return "?#$idx"
            var p = off
            val (_, np) = readUleb(p)
            p = np
            val sb = StringBuilder()
            while (p < buf.size) {
                val a = buf[p].toInt() and 0xff
                if (a == 0) break
                when {
                    a < 0x80 -> {
                        sb.append(a.toChar()); p += 1
                    }
                    a and 0xe0 == 0xc0 -> {
                        if (p + 1 >= buf.size) break
                        val b = buf[p + 1].toInt() and 0xff
                        sb.append((((a and 0x1f) shl 6) or (b and 0x3f)).toChar()); p += 2
                    }
                    else -> {
                        if (p + 2 >= buf.size) break
                        val b = buf[p + 1].toInt() and 0xff
                        val c = buf[p + 2].toInt() and 0xff
                        sb.append((((a and 0x0f) shl 12) or ((b and 0x3f) shl 6) or (c and 0x3f)).toChar()); p += 3
                    }
                }
            }
            return sb.toString()
        }

        fun typeName(typeIdx: Int, classHint: Int = -1): String {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return "?type$typeIdx"
            val descIdx = u32(typeIdsOff + typeIdx * 4)
            return humanize(string(descIdx))
        }

        // ---- 原始读取

        fun u16(off: Int): Int {
            if (off < 0 || off + 2 > buf.size) return 0
            return ((buf[off].toInt() and 0xff) or ((buf[off + 1].toInt() and 0xff) shl 8))
        }

        fun u32(off: Int): Int {
            if (off < 0 || off + 4 > buf.size) return 0
            return ((buf[off].toInt() and 0xff) or
                ((buf[off + 1].toInt() and 0xff) shl 8) or
                ((buf[off + 2].toInt() and 0xff) shl 16) or
                ((buf[off + 3].toInt() and 0xff) shl 24))
        }

        fun readUleb(off: Int): Pair<Int, Int> {
            var p = off
            var result = 0
            var shift = 0
            while (p < buf.size) {
                val b = buf[p].toInt() and 0xff
                p += 1
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) break
            }
            return result to p
        }
    }

    private fun startsWith(b: ByteArray, prefix: ByteArray): Boolean {
        if (b.size < prefix.size) return false
        for (i in prefix.indices) if (b[i] != prefix[i]) return false
        return true
    }

    /** 把 JVM 类型描述符变成好读的形式：Lfoo/Bar; → foo.Bar，[I → int[]。 */
    fun humanize(desc: String): String {
        if (desc.isEmpty()) return desc
        var i = 0
        var arraySuffix = ""
        while (i < desc.length && desc[i] == '[') {
            arraySuffix += "[]"
            i++
        }
        if (i >= desc.length) return desc + arraySuffix
        val base = when (desc[i]) {
            'V' -> "void"
            'Z' -> "boolean"
            'B' -> "byte"
            'S' -> "short"
            'C' -> "char"
            'I' -> "int"
            'J' -> "long"
            'F' -> "float"
            'D' -> "double"
            'L' -> desc.substring(i + 1).removeSuffix(";").replace('/', '.')
            else -> desc.substring(i)
        }
        return base + arraySuffix
    }

    fun accessFlags(flags: Int, isMethod: Boolean): String {
        val out = ArrayList<String>()
        if (flags and 0x1 != 0) out += "public"
        if (flags and 0x2 != 0) out += "private"
        if (flags and 0x4 != 0) out += "protected"
        if (flags and 0x8 != 0) out += "static"
        if (flags and 0x10 != 0) out += "final"
        if (flags and 0x20 != 0) if (isMethod) out += "synchronized" else out += "volatile"
        if (flags and 0x40 != 0) if (isMethod) out += "bridge" else out += "transient"
        if (flags and 0x80 != 0) if (isMethod) out += "varargs" else out += "transient"
        if (flags and 0x100 != 0) out += "native"
        if (flags and 0x200 != 0) out += "interface"
        if (flags and 0x400 != 0) out += "abstract"
        if (flags and 0x800 != 0) out += "strictfp"
        if (flags and 0x1000 != 0) out += "synthetic"
        if (flags and 0x2000 != 0) out += "annotation"
        if (flags and 0x4000 != 0) out += "enum"
        if (flags and 0x10000 != 0) out += "constructor"
        if (flags and 0x20000 != 0) out += "declared_synchronized"
        return out.joinToString(" ")
    }
}

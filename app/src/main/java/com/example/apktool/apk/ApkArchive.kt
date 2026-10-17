package com.example.apktool.apk

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** ZIP 条目摘要。 */
data class ZipItem(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val compressedSize: Long,
    val crc: Long,
    val method: Int,
)

/** 一段签名信息。 */
data class CertInfo(
    val subject: String,
    val issuer: String,
    val serial: String,
    val notBefore: String,
    val notAfter: String,
    val sigAlg: String,
    val sha256: String,
    val keyAlg: String,
    val keyBits: Int,
)

data class SignatureReport(
    val fileSha256: String,
    val schemes: List<String>,
    val sigBlockOffset: Long,
    val sigBlockIds: List<String>,
    val v1Certs: List<CertInfo>,
    val signerCount: Int,
    val notes: List<String>,
)

/**
 * APK 的 ZIP 层与签名层只读解析。
 * 不依赖 build-tools：ZIP 用 java.util.zip，v1 证书用 JCA 的 CertificateFactory 读 PKCS#7，
 * v2/v3/SourceStamp 通过在文件尾部定位 APK Signing Block 魔数来判定存在性。
 */
object ApkArchive {

    private val SIG_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /** APK Signing Block 里的 id → 名称。 */
    private val ID_NAMES = mapOf(
        0x7109871aL to "v2 (APK Signature Scheme v2)",
        0xf05368c0L to "v3 (APK Signature Scheme v3)",
        0x1b93ad61L to "v3.1 (签名轮换)",
        0x6dff800dL to "SourceStamp（源码戳）",
        0x42726577L to "verity padding",
    )

    fun entries(apk: File): List<ZipItem> {
        val out = ArrayList<ZipItem>()
        ZipFile(apk).use { zf ->
            val e: java.util.Enumeration<out ZipEntry> = zf.entries()
            while (e.hasMoreElements()) {
                val it = e.nextElement()
                out += ZipItem(
                    name = it.name,
                    isDir = it.isDirectory,
                    size = it.size,
                    compressedSize = it.compressedSize,
                    crc = it.crc,
                    method = it.method,
                )
            }
        }
        return out
    }

    fun readEntry(apk: File, name: String, maxBytes: Long = 64L * 1024 * 1024): ByteArray? {
        ZipFile(apk).use { zf ->
            val entry = zf.getEntry(name) ?: return null
            if (entry.size > maxBytes) throw IllegalStateException("条目 $name 太大（${entry.size} 字节）")
            return zf.getInputStream(entry).use { it.readBytes() }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 尾部扫描 APK Signing Block，列出其中所有 id-value 对。 */
    private fun sigBlock(apk: File): Pair<Long, List<Long>> {
        val len = apk.length()
        if (len < 32) return -1L to emptyList()
        val window = minOf(len, 128L * 1024).toInt()
        val buf = ByteArray(window)
        RandomAccessFile(apk, "r").use { raf ->
            raf.seek(len - window)
            raf.readFully(buf)
        }
        val magicAt = indexOfLast(buf, SIG_BLOCK_MAGIC) ?: return -1L to emptyList()
        // 布局：[uint64 size][id-value pairs…][uint64 size][16 字节魔数]
        // 魔数前的 uint64 是 size（不含最前面那个 size 字段本身），
        // 因此块起始 = 魔数位置 + 8 - size（块的第一个 size 字段在起始处）。
        if (magicAt < 8) return -1L to emptyList()
        val size2 = readU64(buf, magicAt - 8)
        val blockStartInBuf = magicAt + 8 - size2.toInt()
        if (size2 <= 0 || blockStartInBuf < 0) return -1L to emptyList()
        val size1 = readU64(buf, blockStartInBuf)
        if (size1 != size2) return -1L to emptyList()
        val blockStartInFile = (len - window) + blockStartInBuf

        val ids = ArrayList<Long>()
        var p = blockStartInBuf + 8
        val pairsEnd = magicAt - 8
        while (p + 12 <= pairsEnd) {
            val pairLen = readU64(buf, p).toInt()
            if (pairLen <= 4 || p + 8 + pairLen > pairsEnd) break
            val id = readU32(buf, p + 8)
            ids += id
            p += 8 + pairLen
        }
        return blockStartInFile to ids
    }

    private fun indexOfLast(hay: ByteArray, needle: ByteArray): Int? {
        outer@ for (i in hay.size - needle.size downTo 0) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xff) shl (8 * i))
        return v
    }

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xff) shl (8 * i))
        return v
    }

    private fun certInfo(cert: X509Certificate): CertInfo {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        val bits = when (val k = cert.publicKey) {
            is java.security.interfaces.RSAPublicKey -> k.modulus.bitLength()
            is java.security.interfaces.ECPublicKey -> k.params.order.bitLength()
            else -> -1
        }
        return CertInfo(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            serial = cert.serialNumber.toString(16),
            notBefore = cert.notBefore.toString(),
            notAfter = cert.notAfter.toString(),
            sigAlg = cert.sigAlgName,
            sha256 = sha256,
            keyAlg = cert.publicKey.algorithm,
            keyBits = bits,
        )
    }

    fun signature(apk: File): SignatureReport {
        val notes = ArrayList<String>()
        val (blockOffset, ids) = sigBlock(apk)
        val schemes = ArrayList<String>()
        if (ids.any { it == 0x7109871aL }) schemes += "v2"
        if (ids.any { it == 0xf05368c0L }) schemes += "v3"
        if (ids.any { it == 0x1b93ad61L }) schemes += "v3.1"
        if (ids.any { it == 0x6dff800dL }) schemes += "SourceStamp"

        // v1：META-INF 下的 PKCS#7 签名块
        val v1Certs = ArrayList<CertInfo>()
        ZipFile(apk).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val it = e.nextElement()
                val n = it.name.uppercase()
                if (!n.startsWith("META-INF/")) continue
                if (!(n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) continue
                try {
                    zf.getInputStream(it).use { ins ->
                        CertificateFactory.getInstance("X.509").generateCertificates(ins)
                            .forEach { c -> if (c is X509Certificate) v1Certs += certInfo(c) }
                    }
                } catch (t: Throwable) {
                    notes += "v1 证书块 ${it.name} 解析失败：${t.message}"
                }
            }
        }
        if (v1Certs.isNotEmpty()) schemes.add(0, "v1")

        if (schemes.contains("v1") && schemes.any { it == "v2" || it == "v3" || it == "v3.1" }) {
            notes += "v1 与 v2/v3 同时存在：Android 7+ 优先校验 v2/v3，v1 只是兼容旧系统。"
        }
        notes += "v4 签名是独立文件 <apk>.idsig，不在 APK 内部，本工具无法从 APK 判定。"
        if (schemes.isEmpty()) notes += "未发现任何签名方案 —— 这是一个未签名 APK。"

        return SignatureReport(
            fileSha256 = sha256(apk),
            schemes = schemes,
            sigBlockOffset = blockOffset,
            sigBlockIds = ids.map { id -> ID_NAMES[id] ?: "0x%08x".format(id) },
            v1Certs = v1Certs,
            signerCount = v1Certs.size,
            notes = notes,
        )
    }
}

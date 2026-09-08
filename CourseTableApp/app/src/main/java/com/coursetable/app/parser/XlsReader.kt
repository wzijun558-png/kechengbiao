package com.coursetable.app.parser

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 原生 .xls（OLE2 复合文档 + BIFF8 记录流）读取器 —— 从经过真实文件验证的 Python 解析器移植，
 * 纯标准库实现（ByteBuffer 仅用于转 double）。
 */
object XlsReader {

    private const val FREE = 0xFFFFFFFFL
    private const val END_OF_CHAIN = 0xFFFFFFFEL

    private class LE(val b: ByteArray) {
        fun u8(i: Int): Int = b[i].toInt() and 0xFF
        fun u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
        fun u32(i: Int): Long = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        fun s32(i: Int): Int = u32(i).toInt()
        fun u64(i: Int): Long {
            val lo = u32(i)
            val hi = u32(i + 4)
            return lo or (hi shl 32)
        }
        fun dbl(i: Int): Double = java.nio.ByteBuffer.wrap(b, i, 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).double
    }

    fun read(bytes: ByteArray, fileName: String): WorkbookGrid {
        if (bytes.size < 512 || !bytes.copyOfRange(0, 8)
                .contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(),
                    0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()))
        ) {
            throw IOException("不是 OLE2(.xls) 文件")
        }
        val le = LE(bytes)
        val sectorShift = le.u16(30)
        val miniShift = le.u16(32)
        val sectorSize = 1 shl sectorShift
        val miniSize = 1 shl miniShift
        val firstDirSector = le.s32(48)
        val miniCutoff = le.u32(56)
        val firstMiniFatSector = le.s32(60)
        val numMiniFatSectors = le.s32(64)
        val firstDifatSector = le.s32(68)
        val numDifatSectors = le.s32(72)
        if (sectorSize <= 0 || sectorSize > 1 shl 16) throw IOException("OLE2 扇区大小异常")

        fun readSector(n: Int): ByteArray? {
            val off = (n + 1L) * sectorSize
            if (off < 0 || off + sectorSize > bytes.size) return null
            return bytes.copyOfRange(off.toInt(), off.toInt() + sectorSize)
        }

        // ---- DIFAT -> FAT ----
        val difat = ArrayList<Long>(numDifatSectors * 128 + 109)
        for (i in 0 until 109) difat.add(le.u32(76 + i * 4))
        var dsec = firstDifatSector
        var guard = 0
        while (dsec != FREE.toInt() && dsec != END_OF_CHAIN.toInt() && guard < 1024) {
            guard++
            val blob = readSector(dsec) ?: break
            val ble = LE(blob)
            val per = sectorSize / 4
            for (i in 0 until per - 1) difat.add(ble.u32(i * 4))
            dsec = ble.s32((per - 1) * 4)
        }
        val fat = ArrayList<Long>()
        for (fs in difat) {
            if (fs == FREE || fs == END_OF_CHAIN || fs == 0xFFFFFFFDL || fs == 0xFFFFFFFCL) continue
            val blob = readSector(fs.toInt()) ?: continue
            val ble = LE(blob)
            val n = sectorSize / 4
            for (i in 0 until n) fat.add(ble.u32(i * 4))
        }

        fun chainOf(start: Int, maxLen: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var cur = start
            val seen = HashSet<Int>()
            var cnt = 0
            while (cur != FREE.toInt() && cur != END_OF_CHAIN.toInt() && !seen.contains(cur) && cnt < maxLen / sectorSize + 64) {
                seen.add(cur)
                if (cur >= fat.size) break
                val blob = readSector(cur) ?: break
                out.write(blob)
                cur = fat[cur].toInt()
                cnt++
            }
            return out.toByteArray()
        }

        // ---- directory entries ----
        data class Entry(val name: String, val type: Int, val start: Int, val size: Long)

        val dirBlob = chainOf(firstDirSector, 1 shl 24)
        val dle = LE(dirBlob)
        val nDir = dirBlob.size / 128
        val entries = ArrayList<Entry>()
        var root: Entry? = null
        for (i in 0 until nDir) {
            val o = i * 128
            val nameLen = dle.u16(o + 64)
            val type = dle.u8(o + 66)
            if (type == 0 && nameLen == 0) continue
            // 尾部填充可能残留大长度：安全截取（与 Python 容错切片一致）
            val avail = (dirBlob.size - o).coerceAtLeast(0)
            val take = ((nameLen - 2).coerceAtMost(avail)).coerceAtLeast(0)
            val nameBytes = if (take > 0) dirBlob.copyOfRange(o, o + take) else ByteArray(0)
            val name = String(nameBytes, Charsets.UTF_16LE).substringBefore('\u0000')
            val start = dle.s32(o + 116)
            val size = dle.u64(o + 120)
            val e = Entry(name, type, start, size)
            entries.add(e)
            if (type == 5 && name == "Root Entry") root = e
        }
        val rootEntry = root ?: throw IOException("OLE2 缺少 Root Entry")

        // ---- mini stream & miniFAT ----
        val miniStream = if (rootEntry.size > 0) chainOf(rootEntry.start, rootEntry.size.toInt()) else ByteArray(0)
        val minifat = ArrayList<Long>()
        var mf = firstMiniFatSector
        var mg = 0
        while (mf != FREE.toInt() && mf != END_OF_CHAIN.toInt() && mg < (numMiniFatSectors * 8 + 64)) {
            mg++
            val blob = readSector(mf) ?: break
            val ble = LE(blob)
            val n = sectorSize / 4
            for (i in 0 until n) minifat.add(ble.u32(i * 4))
            if (mf >= fat.size) break
            mf = fat[mf].toInt()
        }

        fun streamOf(e: Entry): ByteArray {
            if (e.size >= miniCutoff) {
                val c = chainOf(e.start, e.size.toInt())
                return if (c.size > e.size) c.copyOf(e.size.toInt()) else c
            }
            // mini stream
            val out = ByteArrayOutputStream()
            var cur = e.start
            var remaining = e.size
            var guard2 = 0
            while (cur != FREE.toInt() && cur != END_OF_CHAIN.toInt() && remaining > 0 && guard2 < 65536) {
                guard2++
                val off = cur.toLong() * miniSize
                if (off >= miniStream.size) break
                val take = minOf(miniSize.toLong(), remaining).toInt()
                val avail = miniStream.size - off.toInt()
                val n = minOf(take, avail)
                out.write(miniStream, off.toInt(), n)
                remaining -= n
                if (cur >= minifat.size) break
                cur = minifat[cur].toInt()
            }
            return out.toByteArray()
        }

        val wbEntry = entries.firstOrNull { it.name.equals("Workbook", true) }
            ?: entries.firstOrNull { it.name.equals("Book", true) }
            ?: throw IOException("工作簿流缺失")
        val wb = streamOf(wbEntry)
        return parseBiff8(wb)
    }

    // ------------------------------------------------------------------
    // BIFF8 records
    // ------------------------------------------------------------------
    private fun parseBiff8(wb: ByteArray): WorkbookGrid {
        val le = LE(wb)
        val sheets = ArrayList<SheetGrid>()
        val sst = ArrayList<String>()
        val boundsheets = ArrayList<Pair<Long, String>>() // (pos, name)

        var current: SheetGrid? = null
        var sheetNameIdx = 0
        var p = 0
        val n = wb.size

        // helper: 读取以 off 起始的 XLUnicodeString（cch u16, flags u8, data）。maxEnd 限制记录边界，
        // 避免个别文件 cch 超长时读到下一条记录（与 Python 切片自然截断行为一致）。
        fun unicodeString(off: Int, maxEnd: Int = n): Pair<String, Int> {
            var o = off
            if (o + 3 > maxEnd) return "" to o
            val cch = le.u16(o); o += 2
            val flags = le.u8(o); o += 1
            val fHigh = flags and 1
            var c = cch
            if (o + c * 2 > maxEnd && fHigh != 0) c = (maxEnd - o) / 2
            if (o + c > maxEnd && fHigh == 0) c = maxEnd - o
            if (c < 0) c = 0
            val text = if (fHigh != 0) {
                String(wb, o, c * 2, Charsets.UTF_16LE)
            } else {
                String(wb, o, c, Charsets.ISO_8859_1)
            }
            o += if (fHigh != 0) c * 2 else c
            return text to o
        }

        fun appendCell(r: Int, c: Int, text: String) {
            current?.put(r, c, text)
        }

        while (p + 4 <= n) {
            val op = le.u16(p)
            val len = le.u16(p + 2)
            val dataStart = p + 4
            val dataEnd = dataStart + len
            if (dataEnd > n) break

            when (op) {
                0x0809 -> { // BOF
                    val dt = if (len >= 4) le.u16(dataStart + 2) else 0
                    if (dt == 0x10) {
                        val nm = if (sheetNameIdx < boundsheets.size) boundsheets[sheetNameIdx].second else "Sheet${sheetNameIdx + 1}"
                        sheetNameIdx++
                        val g = SheetGrid(nm)
                        sheets.add(g)
                        current = g
                    }
                }
                0x000A -> { // EOF
                    current = null
                }
                0x0085 -> { // BOUNDSHEET (globals)
                    if (len >= 8) {
                        val pos = le.u32(dataStart)
                        val (nm, _) = unicodeString(dataStart + 6, dataEnd)
                        boundsheets.add(pos to nm)
                    }
                }
                0x00FC -> { // SST + 紧随其后的 CONTINUE
                    var bufEnd = dataEnd
                    while (bufEnd + 4 <= n && le.u16(bufEnd) == 0x003C) {
                        val cl = le.u16(bufEnd + 2)
                        bufEnd += 4 + cl
                    }
                    if (bufEnd >= dataStart + 8) {
                        val unique = le.u32(dataStart + 4)
                        var o = dataStart + 8
                        var guardS = 0
                        while (guardS < unique && o + 3 <= bufEnd) {
                            guardS++
                            val cch = le.u16(o)
                            val flags = le.u8(o + 2)
                            var oo = o + 3
                            if (flags and 8 != 0) oo += 2      // fRichSt
                            if (flags and 4 != 0) oo += 4      // fExtSt
                            val fHigh = flags and 1
                            val text = if (fHigh != 0) {
                                if (oo + cch * 2 > bufEnd) break
                                String(wb, oo, cch * 2, Charsets.UTF_16LE)
                            } else {
                                if (oo + cch > bufEnd) break
                                String(wb, oo, cch, Charsets.ISO_8859_1)
                            }
                            sst.add(text)
                            o = oo + (if (fHigh != 0) cch * 2 else cch)
                        }
                    }
                    // 已把 SST 及紧随的 CONTINUE 整体消费，跳到其末尾
                    p = bufEnd
                    continue
                }
                0x00FD -> { // LABELSST
                    if (len >= 10) {
                        val r = le.u16(dataStart)
                        val c = le.u16(dataStart + 2)
                        val idx = le.u32(dataStart + 6).toInt()
                        if (idx in sst.indices) appendCell(r, c, sst[idx])
                    }
                }
                0x0204, 0x00D6 -> { // LABEL / RSTRING（罕见，尽力而为）
                    if (len >= 6) {
                        val r = le.u16(dataStart)
                        val c = le.u16(dataStart + 2)
                        val (text, _) = unicodeString(dataStart + 6, dataEnd)
                        appendCell(r, c, text)
                    }
                }
                0x0203 -> { // NUMBER
                    if (len >= 14) {
                        val r = le.u16(dataStart)
                        val c = le.u16(dataStart + 2)
                        appendCell(r, c, numText(le.dbl(dataStart + 6)))
                    }
                }
                0x027E -> { // RK
                    if (len >= 10) {
                        val r = le.u16(dataStart)
                        val c = le.u16(dataStart + 2)
                        val rk = le.u32(dataStart + 6)
                        appendCell(r, c, numText(rkValue(rk)))
                    }
                }
                0x00BD -> { // MULRK
                    if (len >= 6) {
                        val r = le.u16(dataStart)
                        val cFirst = le.u16(dataStart + 2)
                        val body = len - 6
                        val pairs = body / 6
                        for (k in 0 until pairs) {
                            val off = dataStart + 4 + k * 6
                            val c = le.u16(off)
                            val rk = le.u32(off + 2)
                            appendCell(r, c, numText(rkValue(rk)))
                        }
                        if (pairs > 0) {
                            // 末位 colLast 可忽略
                        }
                    }
                }
                0x0205 -> { // BOOLERR
                    if (len >= 8) {
                        val r = le.u16(dataStart)
                        val c = le.u16(dataStart + 2)
                        val isErr = le.u8(dataStart + 6) != 0
                        val valByte = le.u8(dataStart + 7)
                        appendCell(r, c, if (isErr) "#ERR" else if (valByte != 0) "TRUE" else "FALSE")
                    }
                }
                0x00E5 -> { // MERGEDCELLS
                    if (len >= 2) {
                        val cnt = le.u16(dataStart)
                        for (k in 0 until cnt) {
                            val off = dataStart + 2 + k * 8
                            if (off + 8 <= dataEnd) {
                                current?.merged?.add(intArrayOf(
                                    le.u16(off), le.u16(off + 2), le.u16(off + 4), le.u16(off + 6)))
                            }
                        }
                    }
                }
                else -> {
                    // 其他记录（ROW/COLINFO/XF/FONT/FORMAT/DIMENSIONS/FORMULA/STRING 等）暂不处理
                }
            }
            p += 4 + len
        }

        if (sheets.isEmpty()) throw IOException("未找到工作表数据（BIFF 解析无内容）")
        return WorkbookGrid(sheets)
    }

    private fun rkValue(rk: Long): Double {
        return when {
            // bit0 置位 → IEEE double（高 30 bit 数据置于 64bit 高 32 位，低位清零，小端解释）
            rk and 1L != 0L -> java.lang.Double.longBitsToDouble((rk and 0xFFFFFFFCL) shl 32)
            // bits=10 → 整数 * 0.01
            rk and 2L != 0L -> (rk shr 2).toDouble() / 100.0
            // bits=00 → 有符号整数（32 位符号扩展）
            else -> (rk.toInt() shr 2).toDouble()
        }
    }

    private fun numText(v: Double): String {
        if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
            return v.toLong().toString()
        }
        var s = v.toString()
        if (s.endsWith(".0")) s = s.dropLast(2)
        return s
    }
}

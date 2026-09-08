package com.coursetable.app.parser

import com.coursetable.app.model.Schedule
import java.io.IOException
import java.time.LocalDate

/** 导入入口：仅支持 .xls（原生 OLE2/BIFF8）与 .xlsx（OOXML）课表文件。 */
object ImportEngine {

    class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

    class Outcome(
        val schedule: Schedule,
        val usedSheet: String,
        val notes: List<String>
    )

    fun parse(bytes: ByteArray, fileName: String, termStart: LocalDate): Outcome {
        val lower = fileName.lowercase()
        val trimmed = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1).trimStart()
        if (!(lower.endsWith(".xls") || lower.endsWith(".xlsx"))) {
            throw ImportException("仅支持 .xls / .xlsx 课表文件")
        }
        // 文件头兜底识别：真实 xls = OLE2 魔数；xlsx = PK(zip)
        val isOle = bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0xD0 && (bytes[1].toInt() and 0xFF) == 0xCF
        val isZip = trimmed.startsWith("PK\u0003\u0004")
        val wb: WorkbookGrid = if (isOle || lower.endsWith(".xls") && !isZip) {
            XlsReader.read(bytes, fileName)
        } else if (isZip || lower.endsWith(".xlsx")) {
            XlsxReader.read(bytes, fileName)
        } else {
            throw ImportException("无法识别的文件格式")
        }
        val sp = GridScheduleParser.parseWorkbook(wb, termStart, 18, null)
        val s = sp.schedule ?: throw ImportException(sp.note.ifEmpty { "未能识别课表结构" })
        return Outcome(s, sp.sheetName ?: "", listOfNotNull(sp.note))
    }
}

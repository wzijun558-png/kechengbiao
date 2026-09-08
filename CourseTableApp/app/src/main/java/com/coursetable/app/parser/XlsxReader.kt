package com.coursetable.app.parser

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * .xlsx (OOXML) 读取器：zip + DOM，解析 sharedStrings / 单元格（支持 s、inlineStr、str、数值）。
 * DOM 按非命名空间模式解析，属性统一按本地名读取，兼容 JVM 与 Android。
 * 不依赖任何第三方库。
 */
object XlsxReader {

    fun read(bytes: ByteArray, fileName: String): WorkbookGrid {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val out = java.io.ByteArrayOutputStream()
                        zis.copyTo(out)
                        entries[e.name] = out.toByteArray()
                    }
                    e = zis.nextEntry
                }
            }
        } catch (io: IOException) {
            throw IOException("不是有效的 xlsx(zip) 文件", io)
        }
        if (entries.isEmpty()) throw IOException("xlsx 内没有内容")

        // 1. 工作表顺序与名称: xl/workbook.xml
        val sheetOrder = ArrayList<Pair<String, String>>() // (name, rId)
        entries["xl/workbook.xml"]?.let { wb ->
            val doc = newDoc(wb)
            val sheets = elementsByLocal(doc.documentElement, "sheet")
            for (el in sheets) {
                val name = el.getAttribute("name")
                val rid = attr(el, "id")
                if (rid.isNotEmpty()) sheetOrder.add(name to rid)
            }
        } ?: throw IOException("xlsx 缺少 workbook.xml")

        // 2. rId -> worksheet 路径
        val ridToTarget = HashMap<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { rels ->
            val doc = newDoc(rels)
            val relsNodes = elementsByLocal(doc.documentElement, "Relationship")
            for (el in relsNodes) {
                val id = attr(el, "Id")
                val target = attr(el, "Target")
                if (id.isNotEmpty() && target.isNotEmpty()) ridToTarget[id] = target
            }
        }
        fun resolveSheetPath(rid: String): String? {
            val t = ridToTarget[rid] ?: return null
            return when {
                t.startsWith("/") -> t.removePrefix("/")
                t.startsWith("worksheets/") -> "xl/$t"
                t.startsWith("xl/") -> t
                else -> "xl/$t"
            }
        }

        // 3. sharedStrings
        val shared = ArrayList<String>()
        entries["xl/sharedStrings.xml"]?.let { ss ->
            val doc = newDoc(ss)
            val sis = elementsByLocal(doc.documentElement, "si")
            for (si in sis) shared.add(extractText(si))
        }

        // 4. 逐表读取
        val grids = ArrayList<SheetGrid>()
        for ((name, rid) in sheetOrder) {
            val path = resolveSheetPath(rid) ?: continue
            val xml = entries[path] ?: continue
            val grid = SheetGrid(if (name.isBlank()) "Sheet${grids.size + 1}" else name)
            val doc = newDoc(xml)
            val rows = elementsByLocal(doc.documentElement, "row")
            for (rowEl in rows) {
                val rowNum = rowEl.getAttribute("r").toIntOrNull() ?: continue
                val cellNodes = elementsByLocal(rowEl, "c")
                for (cEl in cellNodes) {
                    val ref = cEl.getAttribute("r")
                    if (ref.isEmpty()) continue
                    val parsed = parseRef(ref) ?: continue
                    if (parsed.second + 1 != rowNum) continue
                    val t = cEl.getAttribute("t")
                    var value: String? = null
                    if (t == "inlineStr") {
                        val isEl = firstChildByLocal(cEl, "is")
                        value = if (isEl != null) extractText(isEl) else ""
                    } else {
                        val v = firstChildByLocal(cEl, "v") ?: continue
                        val raw = v.textContent?.trim().orEmpty()
                        value = when (t) {
                            "s" -> raw.toIntOrNull()?.let { if (it in shared.indices) shared[it] else null } ?: raw
                            "b" -> if (raw.equals("1", true)) "TRUE" else "FALSE"
                            else -> raw
                        }
                    }
                    if (value != null) grid.put(parsed.second, parsed.first, value)
                }
            }
            if (grid.cells.isNotEmpty()) grids.add(grid)
        }
        if (grids.isEmpty()) throw IOException("xlsx 中没有可读取的工作表")
        return WorkbookGrid(grids)
    }

    private fun newDoc(bytes: ByteArray): org.w3c.dom.Document {
        val dbf = DocumentBuilderFactory.newInstance()
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (ignored: Throwable) {
        }
        return dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    /** 读取可能带命名空间前缀的属性（如 r:id / Target）。 */
    private fun attr(el: Element, local: String): String {
        val direct = el.getAttribute(local)
        if (direct.isNotEmpty()) return direct
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val an = attrs.item(i).nodeName
            if (an == local || an.endsWith(":$local")) return attrs.item(i).nodeValue ?: ""
        }
        return ""
    }

    /** 按本地名(忽略前缀)收集直接/后代元素。 */
    private fun elementsByLocal(root: Node, local: String): List<Element> {
        val out = ArrayList<Element>()
        walk(root) { n ->
            if (n.nodeType == Node.ELEMENT_NODE) {
                val el = n as Element
                val name = el.tagName
                val idx = name.indexOf(':')
                if ((if (idx >= 0) name.substring(idx + 1) else name) == local) out.add(el)
            }
        }
        return out
    }

    private fun firstChildByLocal(root: Node, local: String): Element? {
        var cur = root.firstChild
        while (cur != null) {
            if (cur.nodeType == Node.ELEMENT_NODE) {
                val el = cur as Element
                val name = el.tagName
                val idx = name.indexOf(':')
                if ((if (idx >= 0) name.substring(idx + 1) else name) == local) return el
            }
            cur = cur.nextSibling
        }
        return null
    }

    private fun walk(root: Node, visit: (Node) -> Unit) {
        visit(root)
        var ch = root.firstChild
        while (ch != null) {
            walk(ch, visit)
            ch = ch.nextSibling
        }
    }

    private fun extractText(node: Node): String {
        val sb = StringBuilder()
        collectText(node, sb)
        return sb.toString()
    }

    private fun collectText(node: Node, sb: StringBuilder) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val ch = children.item(i)
            when (ch.nodeType) {
                Node.ELEMENT_NODE -> {
                    val el = ch as Element
                    val name = el.tagName
                    val local = name.substringAfter(':')
                    if (local == "t") {
                        val txt = el.textContent ?: ""
                        sb.append(if (el.getAttribute("xml:space") == "preserve") txt else txt.trim())
                    } else {
                        collectText(ch, sb)
                    }
                }
                Node.TEXT_NODE -> sb.append(ch.nodeValue)
            }
        }
    }

    /** "A1" -> (col 0-based, row 0-based)。 */
    private fun parseRef(ref: String): Pair<Int, Int>? {
        var col = 0
        var i = 0
        while (i < ref.length && ref[i] in 'A'..'Z') {
            col = col * 26 + (ref[i] - 'A' + 1)
            i++
        }
        if (i == 0) return null
        val row = ref.substring(i).toIntOrNull() ?: return null
        return (col - 1) to (row - 1)
    }
}

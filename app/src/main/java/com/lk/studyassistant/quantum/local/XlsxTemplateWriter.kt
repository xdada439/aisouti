package com.lk.studyassistant.quantum.local

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 生成"题库导入模板"的 xlsx 文件。
 *
 * 兼容性要点（这一版重写于 2026-06-04，修复 WPS / 国产 Office 打开为空的问题）：
 *   1. 改用 sharedStrings.xml 存所有字符串，cell 用 t="s" 引用（这是 Excel 原生最标准的写法）
 *   2. 补 styles.xml / docProps/app.xml / docProps/core.xml，避免"瘦版" xlsx 被严格解析器拒绝
 *   3. sheet1.xml 加 dimension / sheetViews / cols，让 Excel 知道实际数据范围
 */
object XlsxTemplateWriter {
    fun createQuestionBankTemplateBytes(): ByteArray {
        // 11 列：题干 / 题型 / 选项A~H / 正确答案
        val rows = listOf(
            listOf(
                "题干", "题型",
                "选项A", "选项B", "选项C", "选项D",
                "选项E", "选项F", "选项G", "选项H",
                "正确答案"
            ),
            listOf(
                "食醋是什么味道的？", "单选题",
                "酸", "甜", "苦", "辣",
                "", "", "", "",
                "A"
            ),
            listOf(
                "一年包含哪些季节？", "多选题",
                "春", "夏", "秋", "冬",
                "", "", "", "",
                "ABCD"
            ),
            listOf(
                "马铃薯是果实？", "判断题",
                "对", "错", "", "",
                "", "", "", "",
                "错"
            ),
            listOf(
                "中国的首都是____。", "填空题",
                "", "", "", "",
                "", "", "", "",
                "北京"
            )
        )

        // 把所有非空字符串收集进 sharedStrings 池，cell 写引用索引
        val stringPool = LinkedHashMap<String, Int>()
        fun indexOf(s: String): Int {
            return stringPool.getOrPut(s) { stringPool.size }
        }
        // 预先把所有用到的字符串入池（保留出现顺序）
        rows.forEach { row -> row.forEach { if (it.isNotEmpty()) indexOf(it) } }

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("docProps/app.xml", APP_PROPS)
            zip.put("docProps/core.xml", CORE_PROPS)
            zip.put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.put("xl/workbook.xml", WORKBOOK)
            zip.put("xl/styles.xml", STYLES)
            zip.put("xl/sharedStrings.xml", sharedStringsXml(stringPool))
            zip.put("xl/worksheets/sheet1.xml", sheetXml(rows, ::indexOf))
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sharedStringsXml(pool: LinkedHashMap<String, Int>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
            .append(pool.size).append("\" uniqueCount=\"").append(pool.size).append("\">")
        for (s in pool.keys) {
            append("<si><t xml:space=\"preserve\">").append(xmlEscape(s)).append("</t></si>")
        }
        append("</sst>")
    }

    private fun sheetXml(rows: List<List<String>>, indexOf: (String) -> Int): String = buildString {
        val rowCount = rows.size
        val maxCols = rows.maxOf { it.size }
        val lastColLetter = columnName(maxCols - 1)

        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<dimension ref=\"A1:").append(lastColLetter).append(rowCount).append("\"/>")
        append("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\"/></sheetViews>")
        append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        // 列宽：题干/题型 32，选项 14，答案 16
        append("<cols>")
        append("<col min=\"1\" max=\"1\" width=\"36\" customWidth=\"1\"/>")
        append("<col min=\"2\" max=\"2\" width=\"12\" customWidth=\"1\"/>")
        append("<col min=\"3\" max=\"10\" width=\"14\" customWidth=\"1\"/>")
        append("<col min=\"11\" max=\"11\" width=\"16\" customWidth=\"1\"/>")
        append("</cols>")
        append("<sheetData>")
        rows.forEachIndexed { rIndex, row ->
            val excelRow = rIndex + 1
            append("<row r=\"").append(excelRow).append("\">")
            row.forEachIndexed { cIndex, value ->
                if (value.isEmpty()) return@forEachIndexed  // 空 cell 完全省略，XlsxReader 用稀疏 map 不影响列对齐
                val ref = columnName(cIndex) + excelRow
                val sIdx = indexOf(value)
                // 表头行（rIndex=0）用样式 1（粗体），其它用默认 0
                val styleAttr = if (rIndex == 0) " s=\"1\"" else ""
                append("<c r=\"").append(ref).append("\" t=\"s\"").append(styleAttr).append("><v>")
                    .append(sIdx).append("</v></c>")
            }
            append("</row>")
        }
        append("</sheetData>")
        append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        append("</worksheet>")
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private const val APP_PROPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
<Application>AI Souti</Application>
</Properties>"""

    private const val CORE_PROPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>题库导入模板</dc:title>
<dc:creator>AI Souti</dc:creator>
</cp:coreProperties>"""

    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="题库" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>"""

    // 两个样式：0=默认，1=表头（粗体）
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}

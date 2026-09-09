package com.example.speakflow.data

import com.example.speakflow.model.SentencePair
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

object DatasetParser {
    fun parse(input: InputStream, fileName: String): List<SentencePair> =
        if (fileName.lowercase().endsWith(".csv")) parseCsv(input) else parseXlsx(input)

    fun parseCsv(input: InputStream): List<SentencePair> {
        val rows = input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.map(::parseCsvLine).toList()
        }
        return rowsToPairs(rows)
    }

    fun parseXlsx(input: InputStream): List<SentencePair> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/sharedStrings.xml" || entry.name == "xl/worksheets/sheet1.xml") {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: error("첫 번째 시트를 찾을 수 없습니다.")
        val shared = entries["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
        return rowsToPairs(readSheet(sheet, shared))
    }

    internal fun rowsToPairs(source: List<List<String>>): List<SentencePair> {
        val rows = source.map { row -> row.map(String::trim) }.filter { it.any(String::isNotBlank) }
        require(rows.size >= 2 || rows.any { it.size >= 2 }) {
            "한국어와 영어가 들어 있는 두 행(또는 두 열)이 필요합니다."
        }

        val rowPairs = if (rows.size >= 2) {
            val width = maxOf(rows[0].size, rows[1].size)
            (0 until width).mapNotNull { column ->
                pairOrNull(rows[0].getOrElse(column) { "" }, rows[1].getOrElse(column) { "" })
            }
        } else emptyList()

        val columnPairs = rows.mapNotNull { row ->
            pairOrNull(row.getOrElse(0) { "" }, row.getOrElse(1) { "" })
        }

        val chosen = if (rowPairs.size >= columnPairs.size) rowPairs else columnPairs
        return chosen.dropWhile { pair ->
            val ko = pair.korean.lowercase()
            val en = pair.english.lowercase()
            (ko.contains("한국") || ko == "korean") && (en.contains("영어") || en == "english")
        }.also { require(it.isNotEmpty()) { "학습할 문장을 찾지 못했습니다." } }
    }

    private fun pairOrNull(korean: String, english: String): SentencePair? =
        if (korean.isBlank() || english.isBlank()) null else SentencePair(korean, english)

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        parseXml(bytes, object : DefaultHandler() {
            private var inText = false
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (qName == "si") current = StringBuilder()
                if (qName == "t") inText = true
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (qName == "t") inText = false
                if (qName == "si") result += current.toString()
            }
        })
        return result
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val cells = sortedMapOf<Int, MutableMap<Int, String>>()
        var row = 0
        var column = 0
        var type = ""
        var value = StringBuilder()
        var inValue = false
        parseXml(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (qName) {
                    "row" -> row = (attributes?.getValue("r")?.toIntOrNull() ?: row + 1) - 1
                    "c" -> {
                        column = cellColumn(attributes?.getValue("r").orEmpty())
                        type = attributes?.getValue("t").orEmpty()
                        value = StringBuilder()
                    }
                    "v", "t" -> inValue = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (qName == "v" || qName == "t") inValue = false
                if (qName == "c") {
                    val raw = value.toString()
                    val decoded = if (type == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                    cells.getOrPut(row) { sortedMapOf() }[column] = decoded
                }
            }
        })
        val maxColumn = cells.values.flatMap { it.keys }.maxOrNull() ?: 0
        return cells.values.map { cellRow -> (0..maxColumn).map { cellRow[it].orEmpty() } }
    }

    private fun cellColumn(reference: String): Int {
        var value = 0
        reference.takeWhile(Char::isLetter).uppercase().forEach { value = value * 26 + (it - 'A' + 1) }
        return (value - 1).coerceAtLeast(0)
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> { value.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { result += value.toString(); value.clear() }
                else -> value.append(char)
            }
            index++
        }
        result += value.toString()
        return result
    }

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        SAXParserFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }
}

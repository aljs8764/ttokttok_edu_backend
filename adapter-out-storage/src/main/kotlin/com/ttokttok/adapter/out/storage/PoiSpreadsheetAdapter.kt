package com.ttokttok.adapter.out.storage

import com.ttokttok.application.port.out.SpreadsheetPort
import com.ttokttok.application.port.out.SpreadsheetRow
import com.ttokttok.domain.common.InvalidInputException
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId

/**
 * Apache POI 엑셀 어댑터 (STU-002, 이후 ATT-004 출석부 내보내기도 여기서).
 * - 첫 번째 시트, 첫 비어있지 않은 행을 헤더로 본다
 * - 날짜 셀은 YYYY-MM-DD 문자열로, 숫자 셀(전화번호가 숫자로 입력된 경우)은 앞자리 0 보정
 */
@Component
class PoiSpreadsheetAdapter : SpreadsheetPort {
    private val formatter = DataFormatter()

    init {
        // zip bomb 방어 (POI 기본값보다 엄격하게)
        ZipSecureFile.setMinInflateRatio(0.01)
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024)
    }

    override fun read(bytes: ByteArray, maxRows: Int): List<SpreadsheetRow> {
        if (bytes.size > MAX_BYTES) throw InvalidInputException("FILE_TOO_LARGE", "파일은 5MB 이하여야 합니다")
        val wb = try { XSSFWorkbook(ByteArrayInputStream(bytes)) } catch (e: Exception) {
            throw InvalidInputException("INVALID_FILE", ".xlsx 형식의 엑셀 파일만 업로드할 수 있습니다")
        }
        wb.use {
            val sheet = wb.getSheetAt(0)
            val headerRow = (sheet.firstRowNum..sheet.lastRowNum).asSequence()
                .mapNotNull { sheet.getRow(it) }
                .firstOrNull { r -> r.any { formatter.formatCellValue(it).isNotBlank() } }
                ?: throw InvalidInputException("EMPTY_FILE", "헤더 행이 없습니다")
            val headers = headerRow.associate { it.columnIndex to formatter.formatCellValue(it).trim() }.filterValues { it.isNotEmpty() }

            val rows = ((headerRow.rowNum + 1)..sheet.lastRowNum).mapNotNull { i ->
                val r = sheet.getRow(i) ?: return@mapNotNull null
                // 템플릿 안내 문구(#으로 시작) 행은 건너뜀
                if (formatter.formatCellValue(r.getCell(0)).startsWith("#")) return@mapNotNull null
                SpreadsheetRow(i + 1, headers.mapValues { (col, _) -> r.getCell(col)?.let(::text).orEmpty() }.mapKeys { headers.getValue(it.key) })
            }
            val nonEmpty = rows.filterNot { it.cells.values.all(String::isBlank) }
            if (nonEmpty.size > maxRows) throw InvalidInputException("TOO_MANY_ROWS", "최대 ${maxRows}행까지 업로드할 수 있습니다 (현재 ${nonEmpty.size}행)")
            return nonEmpty
        }
    }

    override fun write(sheetName: String, headers: List<String>, rows: List<List<String>>, notes: List<String>): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet(sheetName)
            val headerStyle = wb.createCellStyle().apply {
                setFillForegroundColor(IndexedColors.GREY_25_PERCENT.index)
                setFillPattern(FillPatternType.SOLID_FOREGROUND)
                setFont(wb.createFont().apply { setBold(true) })
            }
            val textStyle = wb.createCellStyle().apply { setDataFormat(wb.createDataFormat().getFormat("@")) }
            sheet.createRow(0).also { r -> headers.forEachIndexed { i, h -> r.createCell(i).apply { setCellValue(h); cellStyle = headerStyle } } }
            rows.forEachIndexed { ri, values ->
                val r = sheet.createRow(ri + 1)
                values.forEachIndexed { ci, v -> r.createCell(ci).apply { setCellValue(v); cellStyle = textStyle } }
            }
            notes.forEachIndexed { i, n -> sheet.createRow(rows.size + 2 + i).createCell(0).setCellValue("# $n") }
            headers.indices.forEach { sheet.setColumnWidth(it, 18 * 256) }
            // 전 열을 텍스트 서식으로 → 전화번호 앞자리 0 보존
            headers.indices.forEach { sheet.setDefaultColumnStyle(it, textStyle) }
            return ByteArrayOutputStream().also { wb.write(it) }.toByteArray()
        }
    }

    private fun text(cell: Cell): String = when {
        cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) ->
            cell.dateCellValue.toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString()
        cell.cellType == CellType.NUMERIC -> {
            val raw = formatter.formatCellValue(cell).replace(",", "")
            // 01012345678 이 숫자로 저장되면 1012345678 이 된다 → 10자리 1xxxxxxxxx 는 0 보정
            if (raw.matches(Regex("^1\\d{8,9}$"))) "0$raw" else raw
        }
        else -> formatter.formatCellValue(cell).trim()
    }

    companion object { private const val MAX_BYTES = 5 * 1024 * 1024 }
}

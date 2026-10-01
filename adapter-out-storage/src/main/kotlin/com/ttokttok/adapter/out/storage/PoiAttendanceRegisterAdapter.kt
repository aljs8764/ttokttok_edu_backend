package com.ttokttok.adapter.out.storage

import com.ttokttok.application.port.out.AttendanceRegister
import com.ttokttok.application.port.out.AttendanceRegisterPort
import com.ttokttok.domain.attendance.MonthlyMark
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 출석부 엑셀 (ATT-004).
 *
 * 교육청 보고 양식 원본은 아직 확보 전(Open Issue #5)이라, 학원 출석부에서 흔한 형태로 만든다:
 * 번호 · 이름 · 1~말일(O/△/X) · 출석 · 지각·조퇴 · 결석 · 출석률 · 비고(결석 사유).
 * 실제 양식이 오면 이 어댑터만 바꾸면 된다 — 애플리케이션 계층은 AttendanceRegister 모델만 넘긴다.
 *
 * 비밀번호를 주면 Agile(AES) 방식으로 파일 전체를 암호화한다 (엑셀 "암호 설정"과 동일하게 열 때 묻는다).
 */
@Component
class PoiAttendanceRegisterAdapter : AttendanceRegisterPort {
    private val zone = ZoneId.of("Asia/Seoul")

    override fun render(register: AttendanceRegister, password: String?): ByteArray {
        val plain = build(register)
        return if (password == null) plain else encrypt(plain, password)
    }

    private fun build(reg: AttendanceRegister): ByteArray = XSSFWorkbook().use { wb ->
        val sheet = wb.createSheet("${reg.month.monthValue}월 출석부")
        val dates = (1..reg.month.lengthOfMonth()).map { reg.month.atDay(it) }

        fun style(block: CellStyle.() -> Unit): CellStyle = wb.createCellStyle().apply {
            setBorderTop(BorderStyle.THIN); setBorderBottom(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN); setBorderRight(BorderStyle.THIN)
            setVerticalAlignment(VerticalAlignment.CENTER)
            block()
        }
        val bold = wb.createFont().apply { setBold(true) }
        val titleStyle = wb.createCellStyle().apply { setFont(wb.createFont().apply { setBold(true); setFontHeightInPoints(14.toShort()) }) }
        val header = style {
            setAlignment(HorizontalAlignment.CENTER); setWrapText(true); setFont(bold)
            setFillForegroundColor(IndexedColors.GREY_25_PERCENT.index); setFillPattern(FillPatternType.SOLID_FOREGROUND)
        }
        val center = style { setAlignment(HorizontalAlignment.CENTER) }
        val noClass = style {
            setAlignment(HorizontalAlignment.CENTER)
            setFillForegroundColor(IndexedColors.GREY_40_PERCENT.index); setFillPattern(FillPatternType.SOLID_FOREGROUND)
        }
        val absentStyle = style { setAlignment(HorizontalAlignment.CENTER); setFont(wb.createFont().apply { setColor(IndexedColors.RED.index); setBold(true) }) }
        val left = style { setAlignment(HorizontalAlignment.LEFT) }
        val wrapLeft = style { setAlignment(HorizontalAlignment.LEFT); setWrapText(true) }
        val percent = style { setAlignment(HorizontalAlignment.CENTER); setDataFormat(wb.createDataFormat().getFormat("0.0%")) }

        val dayCol0 = 2
        val totalsCol = dayCol0 + dates.size
        val lastCol = totalsCol + 4

        // 제목·정보
        sheet.createRow(0).createCell(0).apply {
            setCellValue("${reg.institutionName} ${reg.classroomName} 출석부 — ${reg.month.year}년 ${reg.month.monthValue}월")
            cellStyle = titleStyle
        }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, lastCol))
        sheet.createRow(1).createCell(0).setCellValue(
            listOfNotNull(
                reg.teacherNames.takeIf { it.isNotEmpty() }?.let { "담당: ${it.joinToString(", ")}" },
                "수업일 ${reg.classDays.size}일",
                "출력 ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(reg.generatedAt.atZone(zone))}",
            ).joinToString("   "),
        )
        sheet.addMergedRegion(CellRangeAddress(1, 1, 0, lastCol))
        sheet.createRow(2).createCell(0).setCellValue("O 출석   △ 지각·조퇴   X 결석   (회색: 수업 없는 날)")
        sheet.addMergedRegion(CellRangeAddress(2, 2, 0, lastCol))

        // 헤더
        val headerRowIdx = 3
        sheet.createRow(headerRowIdx).apply {
            setHeightInPoints(30f)
            createCell(0).apply { setCellValue("번호"); cellStyle = header }
            createCell(1).apply { setCellValue("이름"); cellStyle = header }
            dates.forEachIndexed { i, d ->
                createCell(dayCol0 + i).apply {
                    setCellValue("${d.dayOfMonth}\n${d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN)}")
                    cellStyle = if (d in reg.classDays) header else noClass
                }
            }
            listOf("출석", "지각·조퇴", "결석", "출석률", "비고").forEachIndexed { i, h ->
                createCell(totalsCol + i).apply { setCellValue(h); cellStyle = header }
            }
        }

        // 원생 행
        reg.rows.forEachIndexed { ri, row ->
            sheet.createRow(headerRowIdx + 1 + ri).apply {
                createCell(0).apply { setCellValue((ri + 1).toDouble()); cellStyle = center }
                createCell(1).apply { setCellValue(row.studentName); cellStyle = left }
                dates.forEachIndexed { i, d ->
                    val mark = row.cells[d]?.mark ?: MonthlyMark.NONE
                    createCell(dayCol0 + i).apply {
                        setCellValue(mark.symbol)
                        cellStyle = when {
                            mark == MonthlyMark.X -> absentStyle
                            d !in reg.classDays && mark == MonthlyMark.NONE -> noClass
                            else -> center
                        }
                    }
                }
                createCell(totalsCol).apply { setCellValue(row.presentDays.toDouble()); cellStyle = center }
                createCell(totalsCol + 1).apply { setCellValue(row.partialDays.toDouble()); cellStyle = center }
                createCell(totalsCol + 2).apply { setCellValue(row.absentDays.toDouble()); cellStyle = center }
                val recorded = row.attendedDays + row.absentDays
                createCell(totalsCol + 3).apply {
                    if (recorded > 0) setCellValue(row.attendedDays.toDouble() / recorded) else setCellValue("-")
                    cellStyle = percent
                }
                createCell(totalsCol + 4).apply { setCellValue(row.remarks); cellStyle = wrapLeft }
            }
        }

        // 일별 출석 인원 (O + △)
        sheet.createRow(headerRowIdx + 1 + reg.rows.size).apply {
            createCell(0).apply { setCellValue(""); cellStyle = header }
            createCell(1).apply { setCellValue("일별 출석"); cellStyle = header }
            dates.forEachIndexed { i, d ->
                val n = reg.rows.count { r -> r.cells[d]?.mark.let { it == MonthlyMark.O || it == MonthlyMark.TRIANGLE } }
                createCell(dayCol0 + i).apply { if (d in reg.classDays || n > 0) setCellValue(n.toDouble()); cellStyle = header }
            }
            (totalsCol..lastCol).forEach { c -> createCell(c).apply { cellStyle = header } }
        }

        // 열 너비·인쇄
        sheet.setColumnWidth(0, 5 * 256)
        sheet.setColumnWidth(1, 10 * 256)
        dates.indices.forEach { sheet.setColumnWidth(dayCol0 + it, 4 * 256) }
        (totalsCol until totalsCol + 4).forEach { sheet.setColumnWidth(it, 8 * 256) }
        sheet.setColumnWidth(totalsCol + 4, 30 * 256)
        sheet.createFreezePane(dayCol0, headerRowIdx + 1)
        sheet.printSetup.apply { setLandscape(true); setFitWidth(1.toShort()); setFitHeight(0.toShort()) }
        sheet.setFitToPage(true)
        wb.setPrintArea(0, 0, lastCol, 0, headerRowIdx + 1 + reg.rows.size)

        ByteArrayOutputStream().also { wb.write(it) }.toByteArray()
    }

    private fun encrypt(xlsx: ByteArray, password: String): ByteArray {
        POIFSFileSystem().use { fs ->
            val encryptor = EncryptionInfo(EncryptionMode.agile).encryptor
            encryptor.confirmPassword(password)
            OPCPackage.open(ByteArrayInputStream(xlsx)).use { opc ->
                encryptor.getDataStream(fs).use { opc.save(it) }
            }
            return ByteArrayOutputStream().also { fs.writeFilesystem(it) }.toByteArray()
        }
    }
}

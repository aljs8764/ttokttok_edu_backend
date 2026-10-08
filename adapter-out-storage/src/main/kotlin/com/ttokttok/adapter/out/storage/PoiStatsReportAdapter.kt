package com.ttokttok.adapter.out.storage

import com.ttokttok.application.port.out.StatsReport
import com.ttokttok.application.port.out.StatsReportPort
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 통계 맞춤 리포트 엑셀 (STAT-007). 고른 항목마다 시트 하나: 기간 KPI · 반별 비교 · 재원 현황(상태·학년·월별 증감·퇴원 사유).
 * 비율 칸은 숫자(0~1)에 % 서식을 입혀 엑셀에서 바로 정렬·계산할 수 있게 한다.
 */
@Component
class PoiStatsReportAdapter : StatsReportPort {
    private val zone = ZoneId.of("Asia/Seoul")

    override fun render(report: StatsReport): ByteArray = XSSFWorkbook().use { wb ->
        val styles = Styles(wb)
        report.kpi?.let { kpi(wb, styles, report, it) }
        report.classrooms?.let { classrooms(wb, styles, report, it) }
        if (report.enrollmentStatus != null || report.enrollmentTrend != null) enrollment(wb, styles, report)
        if (wb.numberOfSheets == 0) wb.createSheet("리포트")
        ByteArrayOutputStream().also { wb.write(it) }.toByteArray()
    }

    private class Styles(wb: Workbook) {
        private fun CellStyle.thinBorders() {
            setBorderTop(BorderStyle.THIN); setBorderBottom(BorderStyle.THIN); setBorderLeft(BorderStyle.THIN); setBorderRight(BorderStyle.THIN)
        }

        val title: CellStyle = wb.createCellStyle().apply { setFont(wb.createFont().apply { setBold(true); setFontHeightInPoints(14.toShort()) }) }
        val header: CellStyle = wb.createCellStyle().apply {
            thinBorders(); setAlignment(HorizontalAlignment.CENTER); setFont(wb.createFont().apply { setBold(true) })
            setFillForegroundColor(IndexedColors.GREY_25_PERCENT.index); setFillPattern(FillPatternType.SOLID_FOREGROUND)
        }
        val text: CellStyle = wb.createCellStyle().apply { thinBorders() }
        val number: CellStyle = wb.createCellStyle().apply { thinBorders(); setAlignment(HorizontalAlignment.RIGHT) }
        val percent: CellStyle = wb.createCellStyle().apply {
            thinBorders(); setAlignment(HorizontalAlignment.RIGHT); setDataFormat(wb.createDataFormat().getFormat("0.0%"))
        }
    }

    private fun Sheet.heading(st: Styles, report: StatsReport, name: String) {
        createRow(0).createCell(0).apply { setCellValue("${report.institutionName} — $name"); cellStyle = st.title }
        createRow(1).createCell(0).setCellValue(
            "기간 ${report.from} ~ ${report.to}  ·  생성 ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(report.generatedAt)}",
        )
    }

    private fun Sheet.headerRow(st: Styles, rowIndex: Int, vararg labels: String) {
        val row = createRow(rowIndex)
        labels.forEachIndexed { i, l -> row.createCell(i).apply { setCellValue(l); cellStyle = st.header } }
    }

    private fun Sheet.put(st: Styles, rowIndex: Int, vararg values: Any?) {
        val row = createRow(rowIndex)
        values.forEachIndexed { i, v ->
            val cell = row.createCell(i)
            when (v) {
                null -> { cell.setBlank(); cell.cellStyle = st.text }
                is Percent -> { if (v.value == null) cell.setBlank() else cell.setCellValue(v.value); cell.cellStyle = st.percent }
                is Number -> { cell.setCellValue(v.toDouble()); cell.cellStyle = st.number }
                else -> { cell.setCellValue(v.toString()); cell.cellStyle = st.text }
            }
        }
    }

    private class Percent(val value: Double?)

    private fun Sheet.widths(vararg w: Int) = w.forEachIndexed { i, c -> setColumnWidth(i, c * 256) }

    private fun kpi(wb: Workbook, st: Styles, report: StatsReport, kpi: com.ttokttok.application.port.`in`.KpiReport) {
        val sheet = wb.createSheet("기간 KPI")
        sheet.heading(st, report, "기간별 KPI")
        val s = kpi.summary
        sheet.headerRow(st, 3, "구분", "값")
        sheet.put(st, 4, "예정 인원(연인원)", s.scheduled)
        sheet.put(st, 5, "출석", s.present)
        sheet.put(st, 6, "지각", s.late)
        sheet.put(st, 7, "결석", s.absent)
        sheet.put(st, 8, "등원율", Percent(s.attendanceRate))
        sheet.put(st, 9, "지각률(출석 대비)", Percent(s.lateRate))
        sheet.put(st, 10, "결석률", Percent(s.absentRate))
        sheet.put(st, 11, "발송 알림장 수", s.noticeCount)
        sheet.put(st, 12, "알림장 평균 열람률", Percent(s.noticeReadRate))

        sheet.headerRow(st, 14, "날짜", "예정", "출석", "지각", "조퇴", "결석", "등원율", "발송 알림장", "열람률")
        val reads = kpi.noticeReads.associateBy { it.date }
        kpi.days.forEachIndexed { i, d ->
            val r = reads[d.date]
            sheet.put(st, 15 + i, d.date.toString(), d.scheduled, d.present, d.late, d.earlyLeave, d.absent, Percent(d.attendanceRate), r?.notices ?: 0, Percent(r?.readRate))
        }
        sheet.widths(22, 10, 10, 10, 10, 10, 10, 14, 10)
    }

    private fun classrooms(wb: Workbook, st: Styles, report: StatsReport, list: List<com.ttokttok.application.port.`in`.ClassroomStat>) {
        val sheet = wb.createSheet("반별 비교")
        sheet.heading(st, report, "반별 성과 비교")
        sheet.headerRow(st, 3, "반", "재원생", "예정", "출석", "지각", "결석", "등원율", "알림장 열람률")
        list.forEachIndexed { i, c ->
            sheet.put(st, 4 + i, c.name, c.students, c.scheduled, c.present, c.late, c.absent, Percent(c.attendanceRate), Percent(c.noticeReadRate))
        }
        sheet.widths(22, 10, 10, 10, 10, 10, 10, 14)
    }

    private fun enrollment(wb: Workbook, st: Styles, report: StatsReport) {
        val sheet = wb.createSheet("재원 현황")
        sheet.heading(st, report, "재원 현황")
        var r = 3
        report.enrollmentStatus?.let { s ->
            sheet.headerRow(st, r++, "상태", "인원")
            sheet.put(st, r++, "재원", s.active)
            sheet.put(st, r++, "휴원", s.paused)
            sheet.put(st, r++, "퇴원", s.withdrawn)
            r++
            sheet.headerRow(st, r++, "학년(재원생)", "인원")
            s.byGrade.forEach { sheet.put(st, r++, it.grade, it.count) }
            r++
        }
        report.enrollmentTrend?.let { t ->
            sheet.headerRow(st, r++, "월", "신규", "퇴원", "순증감")
            t.months.forEach { sheet.put(st, r++, it.month.toString(), it.joined, it.withdrawn, it.net) }
            r++
            sheet.headerRow(st, r++, "퇴원 사유", "인원")
            t.reasons.forEach { sheet.put(st, r++, reasonLabel(it.reason), it.count) }
        }
        sheet.widths(22, 10, 10, 10)
    }

    private fun reasonLabel(code: String) = when (code) {
        "MOVING" -> "이사"
        "GRADES" -> "성적"
        "OTHER_ACADEMY" -> "타 학원 이동"
        "SCHEDULE" -> "시간표"
        "COST" -> "비용"
        "GRADUATION" -> "졸업"
        else -> "기타"
    }
}

package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.ClassroomComparison
import com.ttokttok.application.port.`in`.ClassroomStat
import com.ttokttok.application.port.`in`.ClassroomTrend
import com.ttokttok.application.port.`in`.CongestionToday
import com.ttokttok.application.port.`in`.DashboardWidgetsQuery
import com.ttokttok.application.port.`in`.HourCount
import com.ttokttok.application.port.`in`.LowReadNotice
import com.ttokttok.application.port.`in`.LowReadNotices
import com.ttokttok.application.port.`in`.CongestionCell
import com.ttokttok.application.port.`in`.CongestionReport
import com.ttokttok.application.port.`in`.DailyKpi
import com.ttokttok.application.port.`in`.DailyReadRate
import com.ttokttok.application.port.`in`.EnrollmentStatusReport
import com.ttokttok.application.port.`in`.EnrollmentTrendReport
import com.ttokttok.application.port.`in`.ExportStatsReportUseCase
import com.ttokttok.application.port.`in`.ExportedFile
import com.ttokttok.application.port.`in`.GradeCount
import com.ttokttok.application.port.`in`.KpiReport
import com.ttokttok.application.port.`in`.KpiSummary
import com.ttokttok.application.port.`in`.MonthlyEnrollment
import com.ttokttok.application.port.`in`.ReasonCount
import com.ttokttok.application.port.`in`.StatsQuery
import com.ttokttok.application.port.`in`.StatsSection
import com.ttokttok.application.port.out.AuditEntry
import com.ttokttok.application.port.out.AuditLogPort
import com.ttokttok.application.port.out.ClockPort
import com.ttokttok.application.port.out.DailyCounts
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.NoticePort
import com.ttokttok.application.port.out.NoticeRecipientPort
import com.ttokttok.application.port.out.NoticeReadRow
import com.ttokttok.application.port.out.StatsReadPort
import com.ttokttok.application.port.out.StatsReport
import com.ttokttok.application.port.out.StatsReportPort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.notice.NoticeReadSummary
import com.ttokttok.domain.student.StudentStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.Duration
import java.time.temporal.ChronoUnit

/** 통계 조회 범위 규칙 — 기본 최근 30일(오늘 포함), 최대 366일 */
internal class StatsRange(val from: LocalDate, val to: LocalDate) {
    val fromInstant: Instant get() = from.atStartOfDay(KST).toInstant()
    /** 끝 날짜의 다음 날 0시 (배타) */
    val toInstant: Instant get() = to.plusDays(1).atStartOfDay(KST).toInstant()

    companion object {
        private val KST = java.time.ZoneId.of("Asia/Seoul")

        fun of(from: LocalDate?, to: LocalDate?, today: LocalDate, maxDays: Long = 366, defaultDays: Long = 30): StatsRange {
            val end = to ?: today
            val start = from ?: end.minusDays(defaultDays - 1)
            if (start.isAfter(end)) throw InvalidInputException("INVALID_RANGE", "시작일이 종료일보다 늦습니다")
            if (ChronoUnit.DAYS.between(start, end) + 1 > maxDays) throw InvalidInputException("RANGE_TOO_LONG", "조회 기간은 최대 ${maxDays}일입니다")
            return StatsRange(start, end)
        }
    }
}

private fun ratio(part: Int, whole: Int): Double? = if (whole == 0) null else part.toDouble() / whole
private fun DailyCounts.toKpi() = DailyKpi(date, scheduled, present, late, earlyLeave, absent, ratio(present, scheduled))

/** 알림장별 열람률(0~1)의 평균. 대상이 없는 알림장은 뺀다 */
private fun averageReadRate(rows: Collection<NoticeReadRow>): Double? =
    rows.mapNotNull { ratio(it.read, it.targets) }.takeIf { it.isNotEmpty() }?.average()

@Service
class StatsService(
    private val guard: AccessGuard,
    private val stats: StatsReadPort,
    private val clock: ClockPort,
) : StatsQuery {

    @Transactional(readOnly = true)
    override fun kpi(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): KpiReport {
        guard.requireManager(actor, institutionId)
        return buildKpi(institutionId, StatsRange.of(from, to, clock.today()))
    }

    internal fun buildKpi(institutionId: InstitutionId, range: StatsRange): KpiReport {
        val days = stats.dailyAttendance(institutionId, range.from, range.to, clock.today(), null)
        val reads = stats.noticeReads(institutionId, range.fromInstant, range.toInstant)
        val scheduled = days.sumOf { it.scheduled }
        val present = days.sumOf { it.present }
        val late = days.sumOf { it.late }
        val absent = days.sumOf { it.absent }
        return KpiReport(
            from = range.from, to = range.to,
            summary = KpiSummary(
                scheduled = scheduled, present = present, late = late, absent = absent,
                attendanceRate = ratio(present, scheduled), lateRate = ratio(late, present), absentRate = ratio(absent, scheduled),
                noticeCount = reads.size, noticeReadRate = averageReadRate(reads),
            ),
            days = days.map { it.toKpi() },
            noticeReads = reads.groupBy { it.date }.toSortedMap().map { (d, rows) -> DailyReadRate(d, rows.size, averageReadRate(rows)) },
        )
    }

    @Transactional(readOnly = true)
    override fun congestion(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): CongestionReport {
        guard.requireManager(actor, institutionId)
        val range = StatsRange.of(from, to, clock.today(), maxDays = 92, defaultDays = 28)
        val cells = stats.congestion(institutionId, range.fromInstant, range.toInstant)
            .map { CongestionCell(it.weekday, it.hour, it.checkIns, it.checkOuts) }
            .sortedWith(compareBy({ it.weekday }, { it.hour }))
        val weekdayCounts = generateSequence(range.from) { it.plusDays(1) }.takeWhile { !it.isAfter(range.to) }
            .groupingBy { it.dayOfWeek.value }.eachCount().toSortedMap()
        return CongestionReport(
            from = range.from, to = range.to, cells = cells, weekdayCounts = weekdayCounts,
            peaks = cells.sortedByDescending { it.checkIns + it.checkOuts }.take(5),
        )
    }

    @Transactional(readOnly = true)
    override fun classrooms(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?): ClassroomComparison {
        guard.requireManager(actor, institutionId)
        val range = StatsRange.of(from, to, clock.today())
        return ClassroomComparison(range.from, range.to, buildClassrooms(actor, institutionId, range))
    }

    internal fun buildClassrooms(actor: UserId, institutionId: InstitutionId, range: StatsRange): List<ClassroomStat> {
        val names = guard.visibleClassrooms(actor, institutionId).associate { it.id to it.name }
        val att = stats.classroomAttendance(institutionId, range.from, range.to, clock.today()).associateBy { it.classroomId }
        val reads = stats.classroomNoticeReads(institutionId, range.fromInstant, range.toInstant)
        val students = stats.activeStudentsByClassroom(institutionId)
        return names.map { (id, name) ->
            val a = att[id]
            ClassroomStat(
                classroomId = id.value, name = name, students = students[id] ?: 0,
                scheduled = a?.scheduled ?: 0, present = a?.present ?: 0, late = a?.late ?: 0, absent = a?.absent ?: 0,
                attendanceRate = a?.let { ratio(it.present, it.scheduled) },
                noticeReadRate = reads[id]?.let { ratio(it.read, it.targets) },
            )
        }.sortedWith(compareByDescending<ClassroomStat> { it.attendanceRate ?: -1.0 }.thenBy { it.name })
    }

    @Transactional(readOnly = true)
    override fun classroomTrend(actor: UserId, institutionId: InstitutionId, classroomId: ClassroomId, from: LocalDate?, to: LocalDate?): ClassroomTrend {
        guard.requireManager(actor, institutionId)
        val classroom = guard.requireClassroomAccess(actor, institutionId, classroomId)
        val range = StatsRange.of(from, to, clock.today())
        val days = stats.dailyAttendance(institutionId, range.from, range.to, clock.today(), classroom.id)
        return ClassroomTrend(classroom.id.value, classroom.name, range.from, range.to, days.map { it.toKpi() })
    }

    @Transactional(readOnly = true)
    override fun enrollmentStatus(actor: UserId, institutionId: InstitutionId): EnrollmentStatusReport {
        guard.requireManager(actor, institutionId)
        return buildStatus(institutionId)
    }

    internal fun buildStatus(institutionId: InstitutionId): EnrollmentStatusReport {
        val counts = stats.studentStatusCounts(institutionId)
        return EnrollmentStatusReport(
            active = counts[StudentStatus.ACTIVE] ?: 0, paused = counts[StudentStatus.PAUSED] ?: 0, withdrawn = counts[StudentStatus.WITHDRAWN] ?: 0,
            byGrade = stats.activeStudentsByGrade(institutionId).map { GradeCount(it.first, it.second) },
        )
    }

    @Transactional(readOnly = true)
    override fun enrollmentTrend(actor: UserId, institutionId: InstitutionId, from: YearMonth?, to: YearMonth?): EnrollmentTrendReport {
        guard.requireManager(actor, institutionId)
        return buildTrend(institutionId, from, to)
    }

    internal fun buildTrend(institutionId: InstitutionId, from: YearMonth?, to: YearMonth?): EnrollmentTrendReport {
        val end = to ?: YearMonth.from(clock.today())
        val start = from ?: end.minusMonths(11)
        if (start.isAfter(end)) throw InvalidInputException("INVALID_RANGE", "시작월이 종료월보다 늦습니다")
        if (ChronoUnit.MONTHS.between(start, end) + 1 > 36) throw InvalidInputException("RANGE_TOO_LONG", "조회 기간은 최대 36개월입니다")
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val joined = stats.joinedByMonth(institutionId, start.atDay(1).atStartOfDay(zone).toInstant(), end.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())
        val withdrawals = stats.withdrawals(institutionId, start.atDay(1), end.atEndOfMonth())
        val byMonth = withdrawals.groupBy { it.month }.mapValues { (_, rows) -> rows.sumOf { it.count } }
        val months = generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }.map { m ->
            val j = joined[m] ?: 0
            val w = byMonth[m] ?: 0
            MonthlyEnrollment(m, j, w, j - w)
        }.toList()
        val reasons = withdrawals.groupBy { it.reason }.map { (r, rows) -> ReasonCount(r, rows.sumOf { it.count }) }.sortedByDescending { it.count }
        return EnrollmentTrendReport(start, end, months, reasons)
    }
}

@Service
class ExportStatsReportService(
    private val guard: AccessGuard,
    private val statsService: StatsService,
    private val institutions: InstitutionPort,
    private val report: StatsReportPort,
    private val audit: AuditLogPort,
    private val clock: ClockPort,
) : ExportStatsReportUseCase {

    @Transactional
    override fun export(actor: UserId, institutionId: InstitutionId, from: LocalDate?, to: LocalDate?, sections: Set<StatsSection>): ExportedFile {
        guard.requireManager(actor, institutionId)
        if (sections.isEmpty()) throw InvalidInputException("NO_SECTIONS", "리포트에 넣을 항목을 하나 이상 고르세요")
        val institution = institutions.findById(institutionId) ?: throw NotFoundException("기관")
        val range = StatsRange.of(from, to, clock.today())
        val now = clock.now()
        val bytes = report.render(
            StatsReport(
                institutionName = institution.name, from = range.from, to = range.to, generatedAt = now,
                kpi = if (StatsSection.KPI in sections) statsService.buildKpi(institutionId, range) else null,
                classrooms = if (StatsSection.CLASSROOMS in sections) statsService.buildClassrooms(actor, institutionId, range) else null,
                enrollmentStatus = if (StatsSection.ENROLLMENT in sections) statsService.buildStatus(institutionId) else null,
                enrollmentTrend = if (StatsSection.ENROLLMENT in sections) {
                    statsService.buildTrend(institutionId, YearMonth.from(range.from), YearMonth.from(range.to))
                } else null,
            ),
        )
        audit.record(
            AuditEntry(
                institutionId, actor, "STATS_EXPORT", "stats", null,
                mapOf("from" to range.from.toString(), "to" to range.to.toString(), "sections" to sections.map { it.name }), now,
            ),
        )
        return ExportedFile("${institution.name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_")}_통계리포트_${range.from}_${range.to}.xlsx", bytes)
    }
}

@Service
class DashboardWidgetsService(
    private val guard: AccessGuard,
    private val notices: NoticePort,
    private val recipients: NoticeRecipientPort,
    private val stats: StatsReadPort,
    private val clock: ClockPort,
) : DashboardWidgetsQuery {

    @Transactional(readOnly = true)
    override fun lowReadNotices(actor: UserId, institutionId: InstitutionId, limit: Int): LowReadNotices {
        val member = guard.requireStaff(actor, institutionId)
        val now = clock.now()
        val sent = notices.findSentBetween(institutionId, now.minus(Duration.ofDays(7)), now, if (member.role.isManager) null else actor)
        val byNotice = recipients.findByNotices(sent.map { it.id }).groupBy { it.noticeId }
        val low = sent.mapNotNull { n ->
            val summary = NoticeReadSummary.of(byNotice[n.id].orEmpty())
            val rate = summary.rate ?: return@mapNotNull null
            if (rate >= LOW_READ_THRESHOLD) return@mapNotNull null
            LowReadNotice(n.id.value, n.title, n.sentAt ?: n.scheduledAt, summary.targetStudents, summary.readStudents, rate)
        }.sortedBy { it.readRate }.take(limit.coerceIn(1, 20))
        return LowReadNotices(LOW_READ_THRESHOLD, low)
    }

    @Transactional(readOnly = true)
    override fun congestionToday(actor: UserId, institutionId: InstitutionId): CongestionToday {
        guard.requireStaff(actor, institutionId)
        val range = StatsRange.of(clock.today(), clock.today(), clock.today())
        val byHour = stats.congestion(institutionId, range.fromInstant, range.toInstant).associateBy { it.hour }
        val hours = (0..23).map { h -> HourCount(h, byHour[h]?.checkIns ?: 0, byHour[h]?.checkOuts ?: 0) }
            .dropWhile { it.checkIns + it.checkOuts == 0 }.dropLastWhile { it.checkIns + it.checkOuts == 0 }
        return CongestionToday(range.from, hours, hours.maxByOrNull { it.checkIns + it.checkOuts }?.takeIf { it.checkIns + it.checkOuts > 0 }?.hour)
    }

    companion object { const val LOW_READ_THRESHOLD = 0.5 }
}

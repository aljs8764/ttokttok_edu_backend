package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.DashboardWidgetsQuery
import com.ttokttok.application.port.`in`.ExportStatsReportUseCase
import com.ttokttok.application.port.`in`.StatsQuery
import com.ttokttok.application.port.`in`.StatsSection
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InvalidInputException
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * 통계/분석 STAT-001~007 (원장·실장). 비율은 0~1, 기간 기본 최근 30일(오늘 포함)·최대 366일.
 *  - GET /stats/kpi                    기간별 KPI: summary + days[](등원율 추이) + noticeReads[](열람률 추이)
 *  - GET /stats/congestion             요일(1=월~7=일)×시간 등·하원 건수 (기본 28일, 최대 92일)
 *  - GET /stats/classrooms             반별 성과 비교 (등원율 높은 순)
 *  - GET /stats/classrooms/{id}/trend  반별 일별 추이
 *  - GET /stats/enrollment             재원/휴원/퇴원 수 + 학년별 재원생
 *  - GET /stats/enrollment/trend       월별 신규·퇴원·순증감 + 퇴원 사유 (month=YYYY-MM, 기본 최근 12개월)
 *  - GET /stats/report.xlsx            맞춤 리포트 (sections=KPI,CLASSROOMS,ENROLLMENT)
 */
@RestController
@RequestMapping("/api/v1/stats")
class StatsController(
    private val stats: StatsQuery,
    private val export: ExportStatsReportUseCase,
) {
    @GetMapping("/kpi")
    fun kpi(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ) = stats.kpi(jwt.userId(), inst(institutionId), from, to)

    @GetMapping("/congestion")
    fun congestion(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ) = stats.congestion(jwt.userId(), inst(institutionId), from, to)

    @GetMapping("/classrooms")
    fun classrooms(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ) = stats.classrooms(jwt.userId(), inst(institutionId), from, to)

    @GetMapping("/classrooms/{id}/trend")
    fun classroomTrend(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ) = stats.classroomTrend(jwt.userId(), inst(institutionId), ClassroomId(id), from, to)

    @GetMapping("/enrollment")
    fun enrollment(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        stats.enrollmentStatus(jwt.userId(), inst(institutionId))

    @GetMapping("/enrollment/trend")
    fun enrollmentTrend(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?,
    ) = stats.enrollmentTrend(jwt.userId(), inst(institutionId), from?.let(::parseMonth), to?.let(::parseMonth))

    @GetMapping("/report.xlsx")
    fun report(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "KPI,CLASSROOMS,ENROLLMENT") sections: String,
    ) = export.export(jwt.userId(), inst(institutionId), from, to, parseSections(sections)).let { xlsx(it.bytes, it.filename) }

    private fun parseSections(raw: String): Set<StatsSection> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
        runCatching { StatsSection.valueOf(it.uppercase()) }
            .getOrElse { throw InvalidInputException("INVALID_SECTION", "sections 는 KPI, CLASSROOMS, ENROLLMENT 중에서 고르세요") }
    }.toSet()
}

/**
 * 대시보드 위젯 DASH-004·006 (교직원 전체, 교사는 본인 알림장만)
 *  - GET /dashboard/widgets/low-read-notices  최근 7일 발송 중 열람률 50% 미만 (0~1)
 *  - GET /dashboard/widgets/congestion        오늘 시간대별 등·하원
 */
@RestController
@RequestMapping("/api/v1/dashboard/widgets")
class DashboardWidgetsController(private val widgets: DashboardWidgetsQuery) {
    @GetMapping("/low-read-notices")
    fun lowRead(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "5") limit: Int,
    ) = widgets.lowReadNotices(jwt.userId(), inst(institutionId), limit)

    @GetMapping("/congestion")
    fun congestion(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        widgets.congestionToday(jwt.userId(), inst(institutionId))
}

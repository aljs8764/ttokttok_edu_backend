package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.DashboardLayoutUseCase
import com.ttokttok.application.port.`in`.DashboardWidget
import com.ttokttok.application.port.`in`.OwnerDashboardQuery
import com.ttokttok.application.port.`in`.StaffMemoUseCase
import com.ttokttok.application.port.`in`.TeacherDashboardQuery
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * Phase 3 P3-⑥ 역할별 대시보드 (DASH-008~023)
 *  원장 대시보드 (원장·실장): /dashboard/owner/{kpi,classes,inbox,billing,churn-risks,staff}, /me/dashboard/institutions
 *  교사 대시보드 (교직원): /dashboard/teacher?teacherId= (다른 교사는 원장·실장만)
 *  교직원 공지: /staff-memos (원장·실장 작성·집계·취소), /staff-memos/mine, /staff-memos/{id}/ack
 *  위젯 배치: /me/dashboard-layout/{OWNER|TEACHER}
 */
@RestController
class OwnerDashboardController(private val owner: OwnerDashboardQuery) {
    @GetMapping("/api/v1/dashboard/owner/kpi")
    fun kpi(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) = owner.kpi(jwt.userId(), inst(institutionId))

    @GetMapping("/api/v1/dashboard/owner/classes")
    fun classes(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) = owner.classes(jwt.userId(), inst(institutionId))

    @GetMapping("/api/v1/dashboard/owner/inbox")
    fun inbox(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) = owner.inbox(jwt.userId(), inst(institutionId))

    @GetMapping("/api/v1/dashboard/owner/billing")
    fun billing(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) = owner.billing(jwt.userId(), inst(institutionId))

    @GetMapping("/api/v1/dashboard/owner/churn-risks")
    fun churn(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "10") limit: Int,
    ) = owner.churnRisks(jwt.userId(), inst(institutionId), limit)

    @GetMapping("/api/v1/dashboard/owner/staff")
    fun staff(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) = owner.staff(jwt.userId(), inst(institutionId))

    /** DASH-016 기관 헤더 없이: 원장·실장으로 소속된 기관 비교 */
    @GetMapping("/api/v1/me/dashboard/institutions")
    fun institutions(@AuthenticationPrincipal jwt: Jwt) = owner.institutions(jwt.userId())
}

@RestController
class TeacherDashboardController(private val teacher: TeacherDashboardQuery) {
    @GetMapping("/api/v1/dashboard/teacher")
    fun dashboard(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(required = false) teacherId: UUID?,
    ) = teacher.dashboard(jwt.userId(), inst(institutionId), teacherId)
}

@RestController
class StaffMemoController(private val memos: StaffMemoUseCase) {
    data class CreateRequest(
        @field:NotBlank @field:Size(max = 100) val title: String,
        @field:Size(max = 2000) val body: String?,
        val dueDate: LocalDate?,
        /** 비우면 기관의 모든 교직원 */
        @field:Size(max = 200) val targetUserIds: List<UUID>?,
    )
    data class AckRequest(val done: Boolean?)

    @PostMapping("/api/v1/staff-memos")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateRequest) =
        memos.create(jwt.userId(), inst(institutionId), StaffMemoUseCase.Create(req.title, req.body, req.dueDate, req.targetUserIds ?: emptyList()))

    @GetMapping("/api/v1/staff-memos")
    fun list(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "false") includeCanceled: Boolean,
    ) = memos.list(jwt.userId(), inst(institutionId), includeCanceled)

    @GetMapping("/api/v1/staff-memos/mine")
    fun mine(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID,
        @RequestParam(defaultValue = "false") includeDone: Boolean,
    ) = memos.mine(jwt.userId(), inst(institutionId), includeDone)

    @GetMapping("/api/v1/staff-memos/{id}/acks")
    fun acks(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        memos.acks(jwt.userId(), inst(institutionId), id)

    /** body 없이 = 읽음, {done:true|false} = 완료 표시/해제 */
    @PostMapping("/api/v1/staff-memos/{id}/ack")
    fun ack(
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID,
        @RequestBody(required = false) req: AckRequest?,
    ) = memos.ack(jwt.userId(), inst(institutionId), id, req?.done)

    @DeleteMapping("/api/v1/staff-memos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @PathVariable id: UUID) =
        memos.cancel(jwt.userId(), inst(institutionId), id)
}

@RestController
class DashboardLayoutController(private val layouts: DashboardLayoutUseCase) {
    data class WidgetDto(@field:NotBlank val key: String, @field:NotNull val visible: Boolean)
    data class LayoutRequest(@field:Valid @field:Size(max = 30) val widgets: List<WidgetDto>)

    @GetMapping("/api/v1/me/dashboard-layout/{kind}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable kind: String) = mapOf("widgets" to layouts.layout(jwt.userId(), kind))

    @PutMapping("/api/v1/me/dashboard-layout/{kind}")
    fun put(@AuthenticationPrincipal jwt: Jwt, @PathVariable kind: String, @Valid @RequestBody req: LayoutRequest) =
        mapOf("widgets" to layouts.save(jwt.userId(), kind, req.widgets.map { DashboardWidget(it.key, it.visible) }))

    @DeleteMapping("/api/v1/me/dashboard-layout/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@AuthenticationPrincipal jwt: Jwt, @PathVariable kind: String) = layouts.reset(jwt.userId(), kind)
}

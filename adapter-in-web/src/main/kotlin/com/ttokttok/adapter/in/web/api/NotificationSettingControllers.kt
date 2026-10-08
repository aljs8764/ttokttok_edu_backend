package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.LocationConsentUseCase
import com.ttokttok.application.port.`in`.NotificationSettingUseCase
import com.ttokttok.application.port.out.ChannelFlags
import com.ttokttok.application.port.out.NotificationCategory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** SET-004 채널별 알림 옵션 — 관리자 웹 (원장·실장) */
@RestController
@RequestMapping("/api/v1/settings/notification-channels")
class NotificationSettingController(private val settings: NotificationSettingUseCase) {
    data class FlagsRequest(val push: Boolean, val alimtalk: Boolean)
    /** 바꿀 카테고리만 보내면 된다. 키: ATTENDANCE·NOTICE·EVENT·CHAT·SURVEY·ALBUM·ABSENCE */
    data class UpdateRequest(val categories: Map<NotificationCategory, FlagsRequest>)

    private fun Map<NotificationCategory, ChannelFlags>.toResponse() = mapOf(
        "categories" to entries.map { (c, f) -> mapOf("category" to c.name, "label" to c.label, "push" to f.push, "alimtalk" to f.alimtalk) },
    )

    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        settings.get(jwt.userId(), inst(institutionId)).toResponse()

    @PutMapping
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @RequestBody req: UpdateRequest) =
        settings.update(jwt.userId(), inst(institutionId), req.categories.mapValues { ChannelFlags(it.value.push, it.value.alimtalk) }).toResponse()
}

/** PAR-008 위치 정보 제공 동의 — 학부모 앱 */
@RestController
@RequestMapping("/api/v1/me/children/{childId}/location-consent")
class LocationConsentController(private val consents: LocationConsentUseCase) {
    data class ConsentRequest(val granted: Boolean)

    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable childId: UUID) =
        consents.get(jwt.userId(), childId).let { mapOf("childId" to it.childId, "granted" to it.granted, "updatedAt" to it.updatedAt) }

    @PutMapping
    fun set(@AuthenticationPrincipal jwt: Jwt, @PathVariable childId: UUID, @RequestBody req: ConsentRequest) =
        consents.set(jwt.userId(), childId, req.granted).let { mapOf("childId" to it.childId, "granted" to it.granted, "updatedAt" to it.updatedAt) }
}

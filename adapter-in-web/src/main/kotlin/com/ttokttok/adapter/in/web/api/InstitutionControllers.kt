package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.ManageDestinationUseCase
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.destination.Destination
import com.ttokttok.domain.destination.DestinationType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 업무 API 공통: 활성 기관은 X-Institution-Id 헤더, 행위자는 JWT sub */
const val INSTITUTION_HEADER = "X-Institution-Id"
internal fun Jwt.userId() = UserId(UUID.fromString(subject))
internal fun inst(id: UUID) = InstitutionId(id)

@RestController
@RequestMapping("/api/v1/destinations")
class DestinationController(private val destinations: ManageDestinationUseCase) {
    data class CreateDestinationRequest(@field:NotBlank val name: String, val type: DestinationType, val sortOrder: Int = 0)
    data class DestinationResponse(val id: UUID, val name: String, val type: DestinationType, val sortOrder: Int)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID, @Valid @RequestBody req: CreateDestinationRequest) =
        destinations.create(ManageDestinationUseCase.CreateCommand(jwt.userId(), inst(institutionId), req.name, req.type, req.sortOrder)).toResponse()

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestHeader(INSTITUTION_HEADER) institutionId: UUID) =
        destinations.list(jwt.userId(), inst(institutionId)).map { it.toResponse() }

    private fun Destination.toResponse() = DestinationResponse(id.value, name, type, sortOrder)
}

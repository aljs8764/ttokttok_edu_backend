package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.ManageMyChildUseCase
import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.StudentId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 스펙 7-8 보호자: 같은 아이 합치기·나누기·이름.
 * 한 아이가 여러 기관에 다니면 기관마다 원생이 따로 생기므로, 보호자가 "같은 아이"라고 확인해 하나로 묶는다.
 */
@RestController
@RequestMapping("/api/v1/me/children")
class MyChildController(private val children: ManageMyChildUseCase) {
    data class MergeRequest(val sourceChildId: UUID)
    data class SplitRequest(val studentId: UUID)
    data class RenameRequest(@field:NotBlank @field:Size(max = 50) val name: String)

    /** 이름·생일이 같은 아이 묶음 — 앱이 "같은 아이인가요?" 를 묻는다 */
    @GetMapping("/merge-suggestions")
    fun suggestions(@AuthenticationPrincipal jwt: Jwt) = children.mergeSuggestions(jwt.userId()).map {
        mapOf("childIds" to it.childIds.map { id -> id.value }, "name" to it.name, "institutionNames" to it.institutionNames)
    }

    /** source 아이(기관 원생·보호자·학생앱 기기)를 이 아이로 합친다 */
    @PostMapping("/{childId}/merge")
    fun merge(@AuthenticationPrincipal jwt: Jwt, @PathVariable childId: UUID, @RequestBody req: MergeRequest) =
        children.merge(jwt.userId(), ChildId(childId), ChildId(req.sourceChildId)).toResponse()

    /** 잘못 합친 기관 하나를 다른 아이로 떼어낸다 */
    @PostMapping("/{childId}/split")
    fun split(@AuthenticationPrincipal jwt: Jwt, @PathVariable childId: UUID, @RequestBody req: SplitRequest) =
        children.split(jwt.userId(), ChildId(childId), StudentId(req.studentId)).toResponse()

    /** 보호자 앱에서 보이는 이름 (기관의 원생 이름은 바뀌지 않음) */
    @PatchMapping("/{childId}")
    fun rename(@AuthenticationPrincipal jwt: Jwt, @PathVariable childId: UUID, @Valid @RequestBody req: RenameRequest) =
        children.rename(jwt.userId(), ChildId(childId), req.name).toResponse()
}

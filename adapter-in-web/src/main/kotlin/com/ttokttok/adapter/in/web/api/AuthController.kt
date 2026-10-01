package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.adapter.`in`.web.security.TokenService
import com.ttokttok.application.port.`in`.AuthenticatedUser
import com.ttokttok.application.port.`in`.LoadAuthenticatedUserQuery
import com.ttokttok.application.port.`in`.LoginUseCase
import com.ttokttok.application.port.`in`.RegisterParentUseCase
import com.ttokttok.application.port.`in`.SignUpInstitutionUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val signUpInstitution: SignUpInstitutionUseCase,
    private val registerParent: RegisterParentUseCase,
    private val login: LoginUseCase,
    private val loadUser: LoadAuthenticatedUserQuery,
    private val tokens: TokenService,
) {
    data class InstitutionSignUpRequest(
        @field:NotBlank @field:Size(max = 100) val institutionName: String,
        @field:NotBlank @field:Size(max = 50) val ownerName: String,
        @field:NotBlank val email: String,
        @field:NotBlank val password: String,
    )

    data class ParentSignUpRequest(
        @field:NotBlank @field:Size(max = 50) val name: String,
        @field:NotBlank val phone: String,
        @field:NotBlank val password: String,
    )

    data class LoginRequest(@field:NotBlank val loginId: String, @field:NotBlank val password: String, val rememberMe: Boolean = false)
    data class RefreshRequest(@field:NotBlank val refreshToken: String)

    data class AuthResponse(
        val accessToken: String, val refreshToken: String, val accessExpiresAt: Instant,
        val user: UserResponse,
    )

    data class UserResponse(val id: String, val name: String, val mustChangePassword: Boolean, val memberships: List<MembershipResponse>)
    data class MembershipResponse(val institutionId: String, val institutionName: String, val role: String)

    /** 원장 가입: 기관 + OWNER 계정 */
    @PostMapping("/institutions")
    @ResponseStatus(HttpStatus.CREATED)
    fun signUpInstitution(@Valid @RequestBody req: InstitutionSignUpRequest): AuthResponse =
        respond(signUpInstitution.signUp(SignUpInstitutionUseCase.Command(req.institutionName, req.ownerName, req.email, req.password)), false)

    /** 학부모 가입: 등록된 보호자 번호와 일치하면 자녀 자동 연결 */
    @PostMapping("/parents")
    @ResponseStatus(HttpStatus.CREATED)
    fun signUpParent(@Valid @RequestBody req: ParentSignUpRequest): AuthResponse =
        respond(registerParent.register(RegisterParentUseCase.Command(req.name, req.phone, req.password)), false)

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): AuthResponse =
        respond(login.login(LoginUseCase.Command(req.loginId, req.password)), req.rememberMe)

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshRequest): AuthResponse {
        val (userId, rememberMe) = tokens.parseRefresh(req.refreshToken)
        return respond(loadUser.load(userId), rememberMe)
    }

    private fun respond(user: AuthenticatedUser, rememberMe: Boolean): AuthResponse {
        val pair = tokens.issue(user.userId, rememberMe)
        return AuthResponse(pair.accessToken, pair.refreshToken, pair.accessExpiresAt, user.toResponse())
    }
}

internal fun AuthenticatedUser.toResponse() = AuthController.UserResponse(
    id = userId.value.toString(), name = name, mustChangePassword = mustChangePassword,
    memberships = memberships.map { AuthController.MembershipResponse(it.institutionId.value.toString(), it.institutionName, it.role.name) },
)

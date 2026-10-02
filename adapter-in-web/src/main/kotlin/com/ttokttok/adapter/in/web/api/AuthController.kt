package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.adapter.`in`.web.security.TokenService
import com.ttokttok.application.port.`in`.AuthenticatedUser
import com.ttokttok.application.port.`in`.LoadAuthenticatedUserQuery
import com.ttokttok.application.port.`in`.LoginUseCase
import com.ttokttok.application.port.`in`.RegisterParentUseCase
import com.ttokttok.application.port.`in`.SessionUseCase
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
    private val sessions: SessionUseCase,
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

    /** 회전: 쓴 refresh 는 폐기하고 새 쌍을 준다. 폐기된 refresh 재사용 시 그 로그인 계열 전체 폐기 */
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshRequest): AuthResponse {
        val claims = tokens.parseRefresh(req.refreshToken)
        val session = sessions.rotate(claims.jti, claims.userId)
        return issue(loadUser.load(claims.userId), session)
    }

    /** 이 기기 로그아웃 — refresh 폐기 (access 는 15분 내 자연 만료) */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody req: RefreshRequest) {
        val claims = runCatching { tokens.parseRefresh(req.refreshToken) }.getOrNull() ?: return
        sessions.end(claims.jti)
    }

    private fun respond(user: AuthenticatedUser, rememberMe: Boolean): AuthResponse = issue(user, sessions.start(user.userId, rememberMe))

    private fun issue(user: AuthenticatedUser, session: SessionUseCase.Session): AuthResponse {
        val pair = tokens.issue(user.userId, session.rememberMe, session.jti, session.expiresAt)
        return AuthResponse(pair.accessToken, pair.refreshToken, pair.accessExpiresAt, user.toResponse())
    }
}

internal fun AuthenticatedUser.toResponse() = AuthController.UserResponse(
    id = userId.value.toString(), name = name, mustChangePassword = mustChangePassword,
    memberships = memberships.map { AuthController.MembershipResponse(it.institutionId.value.toString(), it.institutionName, it.role.name) },
)

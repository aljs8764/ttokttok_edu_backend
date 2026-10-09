package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.adapter.`in`.web.security.TokenService
import com.ttokttok.application.port.`in`.AuthenticatedUser
import com.ttokttok.application.port.`in`.LoadAuthenticatedUserQuery
import com.ttokttok.application.port.`in`.LoginChallengeUseCase
import com.ttokttok.application.port.`in`.SocialLoginResult
import com.ttokttok.application.port.`in`.SocialLoginUseCase
import com.ttokttok.application.port.`in`.LoginUseCase
import com.ttokttok.application.port.`in`.RegisterParentUseCase
import com.ttokttok.application.port.`in`.SessionUseCase
import com.ttokttok.application.port.`in`.SignUpInstitutionUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
    private val challenges: LoginChallengeUseCase,
    private val social: SocialLoginUseCase,
) {
    data class InstitutionSignUpRequest(
        @field:NotBlank @field:Size(max = 100) val institutionName: String,
        @field:NotBlank @field:Size(max = 50) val ownerName: String,
        @field:NotBlank val email: String,
        @field:NotBlank val password: String,
        val type: com.ttokttok.domain.institution.InstitutionType = com.ttokttok.domain.institution.InstitutionType.ACADEMY,
    )

    data class ParentSignUpRequest(
        @field:NotBlank @field:Size(max = 50) val name: String,
        @field:NotBlank val phone: String,
        @field:NotBlank val password: String,
    )

    data class LoginRequest(@field:NotBlank val loginId: String, @field:NotBlank val password: String, val rememberMe: Boolean = false)
    data class RefreshRequest(@field:NotBlank val refreshToken: String)
    data class OtpLoginRequest(@field:NotBlank val challengeId: String, @field:NotBlank val code: String)
    data class SocialLoginRequest(@field:NotBlank val accessToken: String, val rememberMe: Boolean = true)
    data class SocialSignupRequest(@field:NotBlank val signupToken: String, @field:NotBlank @field:Size(max = 50) val name: String, @field:NotBlank val phone: String)

    /** AUTH-007: 2단계 인증을 켠 계정은 토큰 대신 이 응답(HTTP 202)을 받고 /login/otp 로 이어간다 */
    data class OtpRequiredResponse(val otpRequired: Boolean, val challengeId: String, val expiresAt: Instant)

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
        respond(signUpInstitution.signUp(SignUpInstitutionUseCase.Command(req.institutionName, req.ownerName, req.email, req.password, req.type)), false)

    /** 학부모 가입: 등록된 보호자 번호와 일치하면 자녀 자동 연결 */
    @PostMapping("/parents")
    @ResponseStatus(HttpStatus.CREATED)
    fun signUpParent(@Valid @RequestBody req: ParentSignUpRequest): AuthResponse =
        respond(registerParent.register(RegisterParentUseCase.Command(req.name, req.phone, req.password)), false)

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): ResponseEntity<Any> =
        respondOrChallenge(login.login(LoginUseCase.Command(req.loginId, req.password)), req.rememberMe)

    /** AUTH-007 2단계: 인증 앱 6자리 또는 복구 코드 */
    @PostMapping("/login/otp")
    fun loginOtp(@Valid @RequestBody req: OtpLoginRequest): AuthResponse {
        val id = runCatching { java.util.UUID.fromString(req.challengeId) }.getOrNull()
            ?: throw com.ttokttok.domain.common.UnauthenticatedException("인증 시간이 지났습니다. 처음부터 다시 로그인해 주세요")
        val passed = challenges.verify(id, req.code)
        return respond(passed.user, passed.rememberMe)
    }

    /** AUTH-006 사용 가능한 소셜 로그인 (앱이 버튼을 그릴지 정한다) */
    @PostMapping("/social/providers")
    fun socialProviders() = mapOf("providers" to social.enabledProviders().map { it.name })

    /**
     * AUTH-006 소셜 로그인: 앱이 카카오·네이버 SDK 로 받은 access token.
     * 연결된 계정 → 토큰(또는 OTP 202), 없으면 {signupRequired, signupToken, name, email, phone} — 학부모 앱은 가입으로 이어간다
     */
    @PostMapping("/social/{provider}")
    fun socialLogin(@org.springframework.web.bind.annotation.PathVariable provider: String, @Valid @RequestBody req: SocialLoginRequest): ResponseEntity<Any> =
        when (val r = social.login(parseProvider(provider), req.accessToken)) {
            is SocialLoginResult.LoggedIn -> respondOrChallenge(r.user, req.rememberMe)
            is SocialLoginResult.SignupRequired -> ResponseEntity.ok(
                mapOf("signupRequired" to true, "signupToken" to r.signupToken.toString(), "name" to r.name, "email" to r.email, "phone" to r.phone),
            )
        }

    /** AUTH-006 소셜 가입 (학부모) — 휴대폰 번호로 등록된 자녀를 자동 연결 */
    @PostMapping("/social/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun socialSignup(@Valid @RequestBody req: SocialSignupRequest): AuthResponse {
        val token = runCatching { java.util.UUID.fromString(req.signupToken) }.getOrNull()
            ?: throw com.ttokttok.domain.common.UnauthenticatedException("가입 시간이 지났습니다. 다시 소셜 로그인해 주세요")
        return respond(social.signupParent(token, req.name, req.phone), true)
    }

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

    private fun respondOrChallenge(user: AuthenticatedUser, rememberMe: Boolean): ResponseEntity<Any> {
        val challenge = challenges.challengeIfRequired(user.userId, rememberMe)
            ?: return ResponseEntity.ok(respond(user, rememberMe))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(OtpRequiredResponse(true, challenge.id.toString(), challenge.expiresAt))
    }

    private fun issue(user: AuthenticatedUser, session: SessionUseCase.Session): AuthResponse {
        val pair = tokens.issue(user.userId, session.rememberMe, session.jti, session.expiresAt)
        return AuthResponse(pair.accessToken, pair.refreshToken, pair.accessExpiresAt, user.toResponse())
    }
}

internal fun AuthenticatedUser.toResponse() = AuthController.UserResponse(
    id = userId.value.toString(), name = name, mustChangePassword = mustChangePassword,
    memberships = memberships.map { AuthController.MembershipResponse(it.institutionId.value.toString(), it.institutionName, it.role.name) },
)

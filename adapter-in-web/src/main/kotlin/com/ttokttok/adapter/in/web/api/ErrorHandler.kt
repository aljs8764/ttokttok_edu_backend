package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.application.port.`in`.QrScanException
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.DomainException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UnauthenticatedException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 스펙 5장 오류 형식: {code, message, details} */
data class ErrorResponse(val code: String, val message: String, val details: Map<String, Any?> = emptyMap())

/**
 * 오류 응답. 메시지는 한국어로 만들어지고, 요청 언어가 영어(Accept-Language: en)면 [ErrorMessagesEn] 로 바꿔 보낸다 (SET-007).
 * code 는 언어와 무관하게 같으므로 화면은 계속 code 로 분기하면 된다.
 */
@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun domain(e: DomainException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val status = when (e) {
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is ConflictException -> HttpStatus.CONFLICT
            is InvalidInputException -> HttpStatus.BAD_REQUEST
            is UnauthenticatedException -> HttpStatus.UNAUTHORIZED
        }
        return respond(status, e.code, e.message ?: "", request)
    }

    /** 학생앱 QR 스캔 실패 — 코드(QR_INVALID·OUT_OF_RANGE·NO_CLASS_NOW …)로 앱이 안내를 고른다 */
    @ExceptionHandler(QrScanException::class)
    fun qrScan(e: QrScanException, request: HttpServletRequest) =
        respond(HttpStatus.UNPROCESSABLE_ENTITY, e.reason.name, e.message ?: "", request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val english = RequestLanguage.isEnglish(request)
        val details = e.bindingResult.fieldErrors.associate { it.field to if (english) fieldMessageEn(it) else it.defaultMessage }
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인하세요", request, details)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class, MissingRequestHeaderException::class)
    fun badRequest(e: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val raw = e.message?.substringBefore(":")
        // 스프링 예외 문장은 원래 영어 — 영어 요청엔 그대로, 한국어 요청도 기존처럼 그대로 둔다
        return if (RequestLanguage.isEnglish(request)) {
            ResponseEntity.badRequest().body(ErrorResponse("BAD_REQUEST", raw ?: "Bad request."))
        } else {
            ResponseEntity.badRequest().body(ErrorResponse("BAD_REQUEST", raw ?: "잘못된 요청입니다"))
        }
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(e: DataIntegrityViolationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("무결성 위반: {}", e.mostSpecificCause.message)
        return respond(HttpStatus.CONFLICT, "CONFLICT", "이미 처리되었거나 중복된 요청입니다", request)
    }

    private fun respond(
        status: HttpStatus, code: String, message: String, request: HttpServletRequest,
        details: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<ErrorResponse> {
        val text = if (RequestLanguage.isEnglish(request)) ErrorMessagesEn.translate(code, message, status.value()) else message
        return ResponseEntity.status(status).body(ErrorResponse(code, text, details))
    }

    /** Bean Validation 필드 오류 영어 문장 (JVM 로캘과 무관하게 일정하게) */
    private fun fieldMessageEn(f: FieldError): String {
        val args = f.arguments.orEmpty().drop(1) // 0번은 필드 이름
        return when (f.code) {
            "NotBlank" -> "must not be blank"
            "NotNull" -> "must not be null"
            "NotEmpty" -> "must not be empty"
            // @Size 인자는 속성 이름 순(max, min)
            "Size" -> {
                val max = args.getOrNull(0)
                val min = args.getOrNull(1)
                if (min == null || min == 0) "must be at most $max" else "size must be between $min and $max"
            }
            "Min" -> "must be greater than or equal to ${args.firstOrNull()}"
            "Max" -> "must be less than or equal to ${args.firstOrNull()}"
            "Positive" -> "must be greater than 0"
            "Email" -> "must be a valid email address"
            else -> f.defaultMessage ?: "is invalid"
        }
    }
}

package com.ttokttok.adapter.`in`.web.api

import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.DomainException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.NotFoundException
import com.ttokttok.domain.common.UnauthenticatedException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 스펙 5장 오류 형식: {code, message, details} */
data class ErrorResponse(val code: String, val message: String, val details: Map<String, Any?> = emptyMap())

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun domain(e: DomainException): ResponseEntity<ErrorResponse> {
        val status = when (e) {
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is ConflictException -> HttpStatus.CONFLICT
            is InvalidInputException -> HttpStatus.BAD_REQUEST
            is UnauthenticatedException -> HttpStatus.UNAUTHORIZED
        }
        return ResponseEntity.status(status).body(ErrorResponse(e.code, e.message ?: ""))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(
        ErrorResponse("VALIDATION_FAILED", "입력값을 확인하세요", e.bindingResult.fieldErrors.associate { it.field to it.defaultMessage }),
    )

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class, MissingRequestHeaderException::class)
    fun badRequest(e: Exception) = ResponseEntity.badRequest().body(ErrorResponse("BAD_REQUEST", e.message?.substringBefore(":") ?: "잘못된 요청입니다"))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        log.warn("무결성 위반: {}", e.mostSpecificCause.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse("CONFLICT", "이미 처리되었거나 중복된 요청입니다"))
    }
}

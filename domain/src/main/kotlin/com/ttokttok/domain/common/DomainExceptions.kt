package com.ttokttok.domain.common

/** 도메인/애플리케이션 계층 공통 예외. web 어댑터가 HTTP 상태로 변환한다. */
sealed class DomainException(val code: String, message: String) : RuntimeException(message)

class NotFoundException(what: String) : DomainException("NOT_FOUND", "$what 을(를) 찾을 수 없습니다")
class ForbiddenException(message: String = "권한이 없습니다") : DomainException("FORBIDDEN", message)
class ConflictException(code: String, message: String) : DomainException(code, message)
class InvalidInputException(code: String, message: String) : DomainException(code, message)
class UnauthenticatedException(message: String = "인증에 실패했습니다") : DomainException("UNAUTHENTICATED", message)

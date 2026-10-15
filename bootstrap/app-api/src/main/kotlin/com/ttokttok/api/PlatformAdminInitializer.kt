package com.ttokttok.api

import com.ttokttok.application.port.`in`.PlatformAdminBootstrapUseCase
import com.ttokttok.application.port.`in`.PlatformAdminBootstrapUseCase.Result
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** 기동 시 운영사(슈퍼관리자)가 없으면 초기 계정을 만든다. ttok.platform.bootstrap-admin.enabled=false 로 끈다 */
@Component
@ConditionalOnProperty(prefix = "ttok.platform.bootstrap-admin", name = ["enabled"], havingValue = "true")
class PlatformAdminInitializer(
    private val bootstrap: PlatformAdminBootstrapUseCase,
    @Value("\${ttok.platform.bootstrap-admin.email}") private val email: String,
    @Value("\${ttok.platform.bootstrap-admin.password}") private val password: String,
    @Value("\${ttok.platform.bootstrap-admin.name}") private val name: String,
    @Value("\${ttok.platform.bootstrap-admin.institution-name}") private val institutionName: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            when (bootstrap.ensure(PlatformAdminBootstrapUseCase.Command(email, password, name, institutionName))) {
                Result.ALREADY_EXISTS -> log.info("운영사 계정 확인: 이미 존재 — 초기 계정 생성 생략")
                Result.GRANTED_EXISTING_USER -> log.warn("운영사 계정이 없어 기존 사용자 {} 를 운영사로 지정했습니다", email)
                Result.CREATED -> log.warn("운영사 계정이 없어 초기 계정 {} 을(를) 생성했습니다 — 비밀번호를 바로 변경하세요", email)
            }
        } catch (e: Exception) {
            // 초기 계정 생성 실패로 API 서버 기동까지 막지는 않는다
            log.error("운영사 초기 계정 생성 실패: {}", e.message, e)
        }
    }
}

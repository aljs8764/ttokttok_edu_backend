package com.ttokttok.adapter.out.notification

import com.ttokttok.application.port.out.AppLinksPort
import com.ttokttok.application.port.out.SendAlimtalkPort
import com.ttokttok.application.port.out.SendEmailPort
import com.ttokttok.domain.common.PhoneNumber
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ttok.alimtalk.mode / ttok.email.mode
 *  - log : 발송하지 않고 기록 (로컬·테스트 기본값)
 *  - 실제 발송 구현은 대행사·SES 확정 후 추가 (Open Issue #4) — 포트는 그대로, 어댑터만 추가
 */
@Configuration
class MessageConfig {
    @Bean
    @ConditionalOnProperty(name = ["ttok.alimtalk.mode"], havingValue = "log", matchIfMissing = true)
    fun recordingAlimtalkSender(): SendAlimtalkPort = RecordingAlimtalkSender()

    @Bean
    @ConditionalOnProperty(name = ["ttok.email.mode"], havingValue = "log", matchIfMissing = true)
    fun recordingEmailSender(): SendEmailPort = RecordingEmailSender()
}

class RecordingAlimtalkSender : SendAlimtalkPort {
    private val log = LoggerFactory.getLogger(javaClass)
    data class Sent(val phone: PhoneNumber, val templateCode: String, val variables: Map<String, String>)
    val sent: MutableList<Sent> = CopyOnWriteArrayList()

    override fun send(phone: PhoneNumber, templateCode: String, variables: Map<String, String>): Boolean {
        log.info("[ALIMTALK:log] to={} template={} vars={}", phone.masked, templateCode, variables.keys)
        sent += Sent(phone, templateCode, variables)
        return true
    }
}

class RecordingEmailSender : SendEmailPort {
    private val log = LoggerFactory.getLogger(javaClass)
    data class Sent(val to: String, val subject: String, val body: String)
    val sent: MutableList<Sent> = CopyOnWriteArrayList()

    override fun send(to: String, subject: String, body: String) {
        log.info("[EMAIL:log] to={} subject={}", to.replaceBefore("@", "***"), subject) // 본문(임시 PW)은 로그 금지
        sent += Sent(to, subject, body)
    }
}

@Component
class AppLinksAdapter(
    @Value("\${ttok.links.join-base-url:https://ttok.app/join/}") private val joinBase: String,
    @Value("\${ttok.links.app-install-url:https://ttok.app/download}") private val installUrl: String,
    @Value("\${ttok.links.staff-invite-base-url:https://admin.ttok.app/invite/}") private val staffInviteBase: String,
    @Value("\${ttok.links.qr-base-url:https://ttok.app/qr/}") private val qrBase: String,
) : AppLinksPort {
    override fun joinUrl(token: String) = joinBase.trimEnd('/') + "/" + token
    override fun appInstallUrl() = installUrl
    override fun staffInviteUrl(token: String) = staffInviteBase.trimEnd('/') + "/" + token
    override fun checkinQrUrl(token: String) = qrBase.trimEnd('/') + "/" + token
}

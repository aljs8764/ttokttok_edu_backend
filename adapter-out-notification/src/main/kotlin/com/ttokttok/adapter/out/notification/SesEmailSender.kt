package com.ttokttok.adapter.out.notification

import com.ttokttok.application.port.out.SendEmailPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest

/**
 * AWS SES v2 메일 발송 (ttok.email.mode=ses). 임시 비밀번호·교직원 초대 메일.
 * 발신 주소(ttok.email.from)는 SES 에서 도메인 인증이 끝나 있어야 한다.
 * 자격 증명은 기본 체인(운영: ECS 태스크 역할).
 */
@Component
@ConditionalOnProperty(name = ["ttok.email.mode"], havingValue = "ses")
class SesEmailSender(
    @Value("\${ttok.email.from}") private val from: String,
    @Value("\${ttok.email.region:ap-northeast-2}") region: String,
) : SendEmailPort, DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: SesV2Client = SesV2Client.builder().region(Region.of(region)).build()

    override fun send(to: String, subject: String, body: String) {
        val utf8 = { text: String -> Content.builder().data(text).charset("UTF-8").build() }
        client.sendEmail(
            SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(to).build())
                .content(
                    EmailContent.builder().simple(
                        Message.builder().subject(utf8(subject)).body(Body.builder().text(utf8(body)).build()).build(),
                    ).build(),
                )
                .build(),
        )
        log.info("[EMAIL:ses] to={} subject={}", to.replaceBefore("@", "***"), subject) // 본문(임시 PW)은 로그 금지
    }

    override fun destroy() = client.close()
}

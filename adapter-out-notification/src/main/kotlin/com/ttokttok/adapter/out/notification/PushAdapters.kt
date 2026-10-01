package com.ttokttok.adapter.out.notification

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import com.ttokttok.application.port.out.PushMessage
import com.ttokttok.application.port.out.PushResult
import com.ttokttok.application.port.out.SendPushPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ttok.push.mode
 *  - fcm  : Firebase Admin SDK로 실제 발송 (ttok.push.fcm.credentials-path 필요)
 *  - log  : 발송하지 않고 기록만 (로컬·테스트 기본값)
 */
@Configuration
class PushConfig {
    @Bean
    @ConditionalOnProperty(name = ["ttok.push.mode"], havingValue = "fcm")
    fun fcmPushSender(props: org.springframework.core.env.Environment): SendPushPort {
        val path = props.getRequiredProperty("ttok.push.fcm.credentials-path")
        val app = FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(
            FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(FileInputStream(path))).build(),
        )
        return FcmPushSender(FirebaseMessaging.getInstance(app))
    }

    @Bean
    @ConditionalOnProperty(name = ["ttok.push.mode"], havingValue = "log", matchIfMissing = true)
    fun recordingPushSender(): SendPushPort = RecordingPushSender()
}

class FcmPushSender(private val messaging: FirebaseMessaging) : SendPushPort {
    override fun send(message: PushMessage): PushResult {
        var success = 0
        val invalid = mutableListOf<String>()
        // FCM 멀티캐스트는 1회 500개 제한
        message.tokens.chunked(500).forEach { chunk ->
            val multicast = MulticastMessage.builder()
                .addAllTokens(chunk)
                .setNotification(Notification.builder().setTitle(message.title).setBody(message.body).build())
                .putAllData(message.data)
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                .setApnsConfig(ApnsConfig.builder().putHeader("apns-priority", "10").setAps(Aps.builder().setSound("default").build()).build())
                .build()
            val res = messaging.sendEachForMulticast(multicast)
            success += res.successCount
            res.responses.forEachIndexed { i, r ->
                val code = r.exception?.messagingErrorCode
                if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) invalid += chunk[i]
            }
        }
        return PushResult(success, invalid)
    }
}

/** 실제로 보내지 않고 메모리에 남긴다. 로컬 개발·통합 테스트에서 발송 내용을 검증하는 용도. */
class RecordingPushSender : SendPushPort {
    private val log = LoggerFactory.getLogger(javaClass)
    val sent: MutableList<PushMessage> = CopyOnWriteArrayList()

    override fun send(message: PushMessage): PushResult {
        log.info("[PUSH:log] to={} title={} body={}", message.tokens.size, message.title, message.body)
        sent += message
        return PushResult(message.tokens.size, emptyList())
    }
}

package com.ttokttok.adapter.`in`.web

import com.ttokttok.application.port.`in`.ListClassroomsQuery
import com.ttokttok.application.port.`in`.LoadAuthenticatedUserQuery
import com.ttokttok.domain.common.UserId
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.util.UUID

/**
 * STOMP: ws(s)://host/ws, CONNECT 헤더 Authorization: Bearer {access}
 * SUBSCRIBE 는 권한 내 토픽만 허용:
 *  - /topic/inst.{id}  : 그 기관의 OWNER/ADMIN
 *  - /topic/class.{id} : 그 반에 접근 가능한 교직원
 * TODO(S2): 서버 다중화 시 Redis 브로커 릴레이로 교체 (스펙 2장)
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val jwtDecoder: JwtDecoder,
    private val loadUser: LoadAuthenticatedUserQuery,
    private val listClassrooms: ListClassroomsQuery,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                val acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
                when (acc.command) {
                    StompCommand.CONNECT -> connect(acc)
                    StompCommand.SUBSCRIBE -> authorizeSubscribe(acc)
                    else -> Unit
                }
                return message
            }
        })
    }

    private fun connect(acc: StompHeaderAccessor) {
        val bearer = acc.getFirstNativeHeader("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: throw IllegalArgumentException("Authorization 헤더가 필요합니다")
        val userId = UserId(UUID.fromString(jwtDecoder.decode(bearer).subject))
        val user = loadUser.load(userId)
        val managerInsts = user.memberships.filter { it.role.isManager }.map { "/topic/inst.${it.institutionId.value}" }
        val classTopics = user.memberships.filter { it.role.isStaff }
            .flatMap { m -> listClassrooms.list(userId, m.institutionId) }
            .map { "/topic/class.${it.id.value}" }
        acc.sessionAttributes?.put(ALLOWED_TOPICS, (managerInsts + classTopics).toSet())
        acc.user = UsernamePasswordAuthenticationToken(userId.value.toString(), null, emptyList())
    }

    private fun authorizeSubscribe(acc: StompHeaderAccessor) {
        val dest = acc.destination ?: throw IllegalArgumentException("구독 대상이 없습니다")
        if (dest.startsWith("/user/")) return
        @Suppress("UNCHECKED_CAST")
        val allowed = acc.sessionAttributes?.get(ALLOWED_TOPICS) as? Set<String> ?: emptySet()
        if (dest !in allowed) throw IllegalArgumentException("구독 권한이 없습니다: $dest")
    }

    companion object { private const val ALLOWED_TOPICS = "ttok.allowedTopics" }
}

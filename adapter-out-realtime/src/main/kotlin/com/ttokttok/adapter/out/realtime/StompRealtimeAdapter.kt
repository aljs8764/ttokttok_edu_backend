package com.ttokttok.adapter.out.realtime

import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * STOMP 브로드캐스트. 롤백된 변경이 화면에 뜨지 않도록 커밋 이후(afterCommit)에만 전송한다.
 * 토픽: /topic/inst.{기관ID} (관리자 대시보드), /topic/class.{반ID} (교사 앱 동기화)
 */
@Component
class StompRealtimeAdapter(private val template: SimpMessagingTemplate) : RealtimePort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun attendanceUpdated(institutionId: InstitutionId, classroomId: ClassroomId, payload: Map<String, Any?>) {
        afterCommit {
            template.convertAndSend("/topic/inst.${institutionId.value}", payload)
            template.convertAndSend("/topic/class.${classroomId.value}", payload)
        }
    }

    private fun afterCommit(action: () -> Unit) {
        val safe = { runCatching(action).onFailure { log.warn("실시간 전송 실패: {}", it.message) } }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { safe() }
            })
        } else safe()
    }
}

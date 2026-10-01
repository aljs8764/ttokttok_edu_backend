package com.ttokttok.worker

import com.ttokttok.application.port.out.RealtimePort
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * app-worker: scheduler + notification 어댑터를 조립한 백그라운드 워커.
 * Outbox 폴링(푸시 발송)과 일 단위 출결 배치를 담당한다.
 */
@SpringBootApplication(scanBasePackages = ["com.ttokttok"])
class AppWorkerApplication {
    /** 워커는 STOMP 연결을 갖지 않는다. 실시간 전송은 커밋한 api 인스턴스가 담당. */
    @Bean
    fun realtimePort(): RealtimePort = object : RealtimePort {
        override fun attendanceUpdated(institutionId: InstitutionId, classroomId: ClassroomId, payload: Map<String, Any?>) = Unit
    }
}

fun main(args: Array<String>) {
    runApplication<AppWorkerApplication>(*args)
}

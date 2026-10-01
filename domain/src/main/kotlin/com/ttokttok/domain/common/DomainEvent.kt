package com.ttokttok.domain.common

import java.time.Instant

/** Outbox로 저장되어 워커가 처리하는 도메인 이벤트. */
interface DomainEvent {
    val institutionId: InstitutionId
    val occurredAt: Instant
}

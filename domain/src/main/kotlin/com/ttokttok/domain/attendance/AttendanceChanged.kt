package com.ttokttok.domain.attendance

import com.ttokttok.domain.common.DomainEvent
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.StudentId
import java.time.Instant

/** 등·하원·상태 변경이 확정되면 발행 → 워커가 학부모 푸시를 보낸다. */
data class AttendanceChanged(
    override val institutionId: InstitutionId,
    val institutionName: String,
    val studentId: StudentId,
    val studentName: String,
    val type: AttendanceEventType,
    val toStatus: AttendanceStatus,
    val isLate: Boolean,
    val destinationName: String?,
    override val occurredAt: Instant,
) : DomainEvent {

    /** 학부모 푸시 문구 */
    fun pushTitle(): String = "[$institutionName] ${studentName}"

    fun pushBody(): String = when (type) {
        AttendanceEventType.CHECK_IN -> if (isLate) "${studentName} 학생이 등원했어요 (지각)" else "${studentName} 학생이 등원했어요"
        AttendanceEventType.CHECK_OUT -> "${studentName} 학생이 하원했어요" + (destinationName?.let { " → $it" } ?: "")
        AttendanceEventType.STATUS_CHANGE -> when (toStatus) {
            AttendanceStatus.ABSENT -> "${studentName} 학생이 결석 처리되었어요"
            AttendanceStatus.IN -> "${studentName} 학생이 등원 처리되었어요"
            AttendanceStatus.OUT -> "${studentName} 학생이 하원 처리되었어요"
            AttendanceStatus.SCHEDULED -> "${studentName} 학생의 출결 상태가 변경되었어요"
        }
    }
}

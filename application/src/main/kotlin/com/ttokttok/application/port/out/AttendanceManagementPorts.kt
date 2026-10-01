package com.ttokttok.application.port.out

import com.ttokttok.domain.attendance.MonthlyRow
import com.ttokttok.domain.common.ClassroomId
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * 감사 로그 (SEC-002 선반영). 출결 수동 변경·출석부 다운로드처럼
 * "누가 언제 무엇을 바꾸거나 가져갔는지" 남겨야 하는 행위를 기록한다.
 * diff에는 개인정보 원문을 넣지 않는다 (이름 정도만).
 */
interface AuditLogPort {
    fun record(entry: AuditEntry)
}

data class AuditEntry(
    val institutionId: InstitutionId,
    val actorId: UserId,
    val action: String,
    val resource: String,
    val resourceId: String?,
    val diff: Map<String, Any?>,
    val at: Instant,
)

/** 일별 출결 집계 (23:50 배치). DASH-001 이력, Phase2 STAT의 원천 */
interface DailyAttendanceStatPort {
    fun upsert(stats: List<DailyAttendanceStat>)
}

data class DailyAttendanceStat(
    val institutionId: InstitutionId,
    val classroomId: ClassroomId,
    val date: LocalDate,
    val scheduled: Int,
    val present: Int,
    val late: Int,
    val earlyLeave: Int,
    val absent: Int,
)

/** 출석부 엑셀 생성 (ATT-004). 구현: adapter-out-storage (POI, 비밀번호 암호화 옵션) */
interface AttendanceRegisterPort {
    fun render(register: AttendanceRegister, password: String?): ByteArray
}

data class AttendanceRegister(
    val institutionName: String,
    val classroomName: String,
    val teacherNames: List<String>,
    val month: YearMonth,
    /** 수업이 있는 날 — 나머지 날짜 열은 회색 처리 */
    val classDays: Set<LocalDate>,
    val rows: List<MonthlyRow>,
    val generatedAt: Instant,
)

package com.ttokttok.domain.common

import java.util.UUID

@JvmInline value class InstitutionId(val value: UUID) { companion object { fun new() = InstitutionId(Uuid7.next()) } }
@JvmInline value class UserId(val value: UUID) { companion object { fun new() = UserId(Uuid7.next()) } }
@JvmInline value class MembershipId(val value: UUID) { companion object { fun new() = MembershipId(Uuid7.next()) } }
@JvmInline value class ClassroomId(val value: UUID) { companion object { fun new() = ClassroomId(Uuid7.next()) } }
@JvmInline value class StudentId(val value: UUID) { companion object { fun new() = StudentId(Uuid7.next()) } }
@JvmInline value class GuardianId(val value: UUID) { companion object { fun new() = GuardianId(Uuid7.next()) } }
@JvmInline value class DestinationId(val value: UUID) { companion object { fun new() = DestinationId(Uuid7.next()) } }
@JvmInline value class AttendanceDayId(val value: UUID) { companion object { fun new() = AttendanceDayId(Uuid7.next()) } }
@JvmInline value class AttendanceEventId(val value: UUID) { companion object { fun new() = AttendanceEventId(Uuid7.next()) } }

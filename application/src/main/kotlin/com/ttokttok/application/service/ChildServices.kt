package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.GetMyChildrenQuery
import com.ttokttok.application.port.`in`.ManageMyChildUseCase
import com.ttokttok.application.port.out.ChildPort
import com.ttokttok.application.port.out.GuardianPort
import com.ttokttok.application.port.out.InstitutionPort
import com.ttokttok.application.port.out.QrScanLogPort
import com.ttokttok.application.port.out.StudentDevicePort
import com.ttokttok.application.port.out.StudentLinkCodePort
import com.ttokttok.application.port.out.StudentPort
import com.ttokttok.domain.child.Child
import com.ttokttok.domain.child.ChildId
import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.ForbiddenException
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.StudentId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.student.GuardianLinkStatus
import com.ttokttok.domain.student.Student
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 다기관 아이 (스펙 7-8)
//  - 원생(student) = 기관이 관리, 기관별 기록. 아이(child) = 가족이 관리, 기관 무관.
//  - 보호자 계정이 원생에 연결될 때 아이를 1:1 로 만들고, 같은 아이인지는 보호자가 합친다.
//  - 기관은 다른 기관의 원생을 볼 수 없다. 아이·합치기는 보호자 API 에서만 다룬다.

/** 보호자 연결·해제 시 아이를 맞춰 둔다 (학부모 가입, 원생 등록, 보호자 추가, 연결 해제에서 호출) */
@Component
class ChildLinker(
    private val children: ChildPort,
    private val students: StudentPort,
    private val guardians: GuardianPort,
) {
    /** 원생에 보호자 계정이 연결됐다 → 처음이면 원생 정보로 아이를 만들고, 보호자를 아이에 붙인다 */
    fun onGuardianLinked(student: Student, parent: UserId): Student {
        val s = if (student.childId == null) {
            val c = children.save(Child(ChildId.new(), student.name, student.birthDate))
            students.save(student.copy(childId = c.id))
        } else student
        children.addGuardian(s.childId!!, parent)
        return s
    }

    /** 기관이 보호자 연결을 끊었다 → 이 아이의 다른 기관 원생에도 연결이 없으면 아이에서도 뺀다 */
    fun onGuardianUnlinked(student: Student, parent: UserId) {
        val childId = student.childId ?: return
        val siblings = students.findByChildren(listOf(childId)).map { it.id }
        val stillLinked = guardians.findByStudents(siblings)
            .any { it.userId == parent && it.linkStatus == GuardianLinkStatus.LINKED }
        if (!stillLinked) children.removeGuardian(childId, parent)
    }
}

@Service
class GetMyChildrenService(
    private val guardians: GuardianPort,
    private val students: StudentPort,
    private val institutions: InstitutionPort,
    private val children: ChildPort,
    private val linker: ChildLinker,
) : GetMyChildrenQuery {

    /** 쓰기 트랜잭션: V9 이전에 연결됐거나 경합으로 아이가 없는 원생은 여기서 아이를 만든다 */
    @Transactional
    override fun children(parent: UserId): List<GetMyChildrenQuery.ChildView> {
        val links = guardians.findLinkedByUser(parent)
        val mine = children.findIdsByGuardian(parent).toSet()
        val ss = students.findAllByIds(links.map { it.studentId }.distinct()).map { s ->
            if (s.childId == null || s.childId !in mine) linker.onGuardianLinked(s, parent) else s
        }
        val kids = children.findAllByIds(ss.mapNotNull { it.childId }.distinct()).associateBy { it.id }
        val insts = institutions.findAllByIds(ss.map { it.institutionId }.distinct()).associateBy { it.id }
        return ss.groupBy { it.childId!! }.map { (childId, list) ->
            GetMyChildrenQuery.ChildView(
                childId,
                kids[childId]?.name ?: list.first().name,
                list.map { s ->
                    val inst = insts[s.institutionId]
                    GetMyChildrenQuery.EnrollmentView(
                        s.id, s.name, s.institutionId, inst?.name ?: "", inst?.type?.name ?: "ACADEMY", s.status.name,
                    )
                }.sortedBy { it.institutionName },
            )
        }.sortedBy { it.name }
    }

    @Transactional
    override fun resolveFilter(parent: UserId, id: UUID?): Set<StudentId>? {
        if (id == null) return null
        val kids = children(parent)
        kids.firstOrNull { it.childId.value == id }?.let { k -> return k.enrollments.map { it.studentId }.toSet() }
        kids.flatMap { it.enrollments }.firstOrNull { it.studentId.value == id }?.let { return setOf(it.studentId) }
        throw ForbiddenException("본인 자녀가 아닙니다")
    }
}

@Service
class ManageMyChildService(
    private val query: GetMyChildrenQuery,
    private val children: ChildPort,
    private val students: StudentPort,
    private val guardians: GuardianPort,
    private val devices: StudentDevicePort,
    private val codes: StudentLinkCodePort,
    private val scanLogs: QrScanLogPort,
) : ManageMyChildUseCase {

    @Transactional
    override fun mergeSuggestions(parent: UserId): List<ManageMyChildUseCase.MergeSuggestion> {
        val views = query.children(parent).associateBy { it.childId }
        val kids = children.findAllByIds(views.keys)
        val seen = mutableSetOf<ChildId>()
        val out = mutableListOf<ManageMyChildUseCase.MergeSuggestion>()
        for (a in kids) {
            if (a.id in seen) continue
            val same = kids.filter { it.id != a.id && it.id !in seen && a.looksSameAs(it) }
            if (same.isEmpty()) continue
            val group = listOf(a) + same
            seen += group.map { it.id }
            out += ManageMyChildUseCase.MergeSuggestion(
                group.map { it.id }, a.name,
                group.flatMap { views[it.id]?.enrollments.orEmpty().map { e -> e.institutionName } }.distinct(),
            )
        }
        return out
    }

    @Transactional
    override fun merge(parent: UserId, target: ChildId, source: ChildId): GetMyChildrenQuery.ChildView {
        if (target == source) throw InvalidInputException("SAME_CHILD", "같은 아이입니다")
        val mine = query.children(parent).map { it.childId }.toSet()
        if (target !in mine || source !in mine) throw ForbiddenException("본인 자녀가 아닙니다")

        students.findByChildren(listOf(source)).forEach { students.save(it.copy(childId = target)) }
        // 엄마·아빠 중 source 쪽에만 연결된 보호자도 target 을 보게 한다
        children.guardianUserIds(source).forEach { children.addGuardian(target, it) }
        devices.reassignChild(source, target)
        scanLogs.reassignChild(source, target)
        codes.deleteByChild(source)
        children.delete(source)
        return view(parent, target)
    }

    @Transactional
    override fun split(parent: UserId, childId: ChildId, studentId: StudentId): GetMyChildrenQuery.ChildView {
        val current = query.children(parent).firstOrNull { it.childId == childId } ?: throw ForbiddenException("본인 자녀가 아닙니다")
        if (current.enrollments.none { it.studentId == studentId }) throw ForbiddenException("이 아이의 기관 정보가 아닙니다")
        if (current.enrollments.size < 2) throw ConflictException("NOTHING_TO_SPLIT", "기관이 하나뿐이라 나눌 수 없습니다")

        val student = students.findAllByIds(listOf(studentId)).single()
        val separated = children.save(Child(ChildId.new(), student.name, student.birthDate))
        students.save(student.copy(childId = separated.id))
        // 새 아이는 그 원생에 실제로 연결된 보호자만 본다
        guardians.findByStudent(student.id)
            .filter { it.linkStatus == GuardianLinkStatus.LINKED }
            .mapNotNull { it.userId }.distinct()
            .forEach { children.addGuardian(separated.id, it) }
        return view(parent, separated.id)
    }

    @Transactional
    override fun rename(parent: UserId, childId: ChildId, name: String): GetMyChildrenQuery.ChildView {
        if (query.children(parent).none { it.childId == childId }) throw ForbiddenException("본인 자녀가 아닙니다")
        val child = children.find(childId) ?: throw ForbiddenException("본인 자녀가 아닙니다")
        children.save(child.rename(name))
        return view(parent, childId)
    }

    private fun view(parent: UserId, childId: ChildId) =
        query.children(parent).first { it.childId == childId }
}

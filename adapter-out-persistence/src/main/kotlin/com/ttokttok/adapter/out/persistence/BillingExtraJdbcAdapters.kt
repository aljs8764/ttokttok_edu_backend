package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.`in`.DiscountKind
import com.ttokttok.application.port.`in`.DiscountRuleView
import com.ttokttok.application.port.`in`.DiscountUseCase
import com.ttokttok.application.port.`in`.DiscountValueType
import com.ttokttok.application.port.`in`.PayMethod
import com.ttokttok.application.port.`in`.RefundMethod
import com.ttokttok.application.port.`in`.RefundView
import com.ttokttok.application.port.out.DataExportPort
import com.ttokttok.application.port.out.DiscountFacts
import com.ttokttok.application.port.out.DiscountPort
import com.ttokttok.application.port.out.ExportSheet
import com.ttokttok.application.port.out.RefundInvoice
import com.ttokttok.application.port.out.RefundPort
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun ResultSet.beId(col: String): UUID? = getObject(col, UUID::class.java)
private fun ResultSet.beTs(col: String): Instant? = getTimestamp(col)?.toInstant()
private fun ResultSet.beDate(col: String): LocalDate? = getDate(col)?.toLocalDate()
private fun beSqlDate(d: LocalDate?) = d?.let { Date.valueOf(it) }
private fun beTime(i: Instant) = Timestamp.from(i)
private fun beP(vararg pairs: Pair<String, Any?>): MapSqlParameterSource = MapSqlParameterSource().also { m -> pairs.forEach { (k, v) -> m.addValue(k, v) } }

// ───────── PAY-006 ─────────
@Component
class DiscountJdbcAdapter(jdbc: JdbcTemplate) : DiscountPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private val ruleMapper = RowMapper<Pair<UUID, DiscountRuleView>> { rs, _ ->
        rs.beId("institution_id")!! to DiscountRuleView(
            rs.beId("id")!!, rs.getString("name"), DiscountKind.valueOf(rs.getString("kind")), rs.getInt("threshold"),
            DiscountValueType.valueOf(rs.getString("value_type")), rs.getInt("value"), rs.getBoolean("active"),
        )
    }

    override fun rules(institutionId: InstitutionId, activeOnly: Boolean): List<DiscountRuleView> =
        named.query(
            "select * from discount_rule where institution_id = :i ${if (activeOnly) "and active" else ""} order by kind, threshold, created_at",
            beP("i" to institutionId.value), ruleMapper,
        ).map { it.second }

    override fun findRule(id: UUID): Pair<UUID, DiscountRuleView>? =
        named.query("select * from discount_rule where id = :id", beP("id" to id), ruleMapper).firstOrNull()

    override fun insertRule(institutionId: InstitutionId, command: DiscountUseCase.SaveRule, now: Instant): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into discount_rule (id, institution_id, name, kind, threshold, value_type, value, active, created_at, updated_at)
               values (:id, :i, :n, :k, :t, :vt, :v, :a, :now, :now)""",
            beP("id" to id, "i" to institutionId.value, "n" to command.name, "k" to command.kind.name, "t" to command.threshold,
                "vt" to command.valueType.name, "v" to command.value, "a" to command.active, "now" to beTime(now)),
        )
        return id
    }

    override fun updateRule(id: UUID, command: DiscountUseCase.SaveRule, now: Instant) {
        named.update(
            "update discount_rule set name = :n, kind = :k, threshold = :t, value_type = :vt, value = :v, active = :a, updated_at = :now where id = :id",
            beP("id" to id, "n" to command.name, "k" to command.kind.name, "t" to command.threshold, "vt" to command.valueType.name,
                "v" to command.value, "a" to command.active, "now" to beTime(now)),
        )
    }

    override fun deleteRule(id: UUID) {
        named.update("delete from discount_rule where id = :id", beP("id" to id))
    }

    /**
     * 형제: 같은 기관에서 보호자 전화번호(해시)를 하나라도 같이 쓰는 재원 원생. 먼저 등록한(첫 반 배정일 → 등록일) 순으로 몇째인지 센다.
     * 장기: 첫 반 배정일부터 기준일까지 개월 수. 다과목: 지금 다니는 반 수.
     */
    override fun facts(institutionId: InstitutionId, studentIds: Collection<UUID>, asOf: LocalDate): List<DiscountFacts> {
        if (studentIds.isEmpty()) return emptyList()
        return named.query(
            """with active as (
                   select s.id, s.name, s.created_at,
                          coalesce((select min(e.from_date) from enrollment e where e.student_id = s.id), s.created_at::date) as since
                     from student s
                    where s.institution_id = :i and s.status <> 'WITHDRAWN'
               )
               select a.id, a.name,
                      1 + (select count(distinct b.id)
                             from guardian g1
                             join guardian g2 on g2.phone_hash = g1.phone_hash and g2.student_id <> g1.student_id
                             join active b on b.id = g2.student_id
                            where g1.student_id = a.id
                              and (b.since, b.created_at, b.id) < (a.since, a.created_at, a.id)) as sibling_rank,
                      greatest(0, (extract(year from age(cast(:d as date), a.since)) * 12 + extract(month from age(cast(:d as date), a.since)))::int) as months,
                      (select count(*) from enrollment e join classroom c on c.id = e.classroom_id and c.deleted_at is null
                        where e.student_id = a.id and e.to_date is null) as classes
                 from active a
                where a.id in (:ids)""",
            beP("i" to institutionId.value, "ids" to studentIds, "d" to beSqlDate(asOf)),
        ) { rs, _ ->
            DiscountFacts(rs.beId("id")!!, rs.getString("name"), rs.getInt("sibling_rank"), rs.getInt("months"), rs.getInt("classes"))
        }
    }

    override fun saveInvoiceExtras(invoiceId: UUID, baseAmount: Int, discountAmount: Int, discountNote: String?, periodStart: LocalDate?, periodEnd: LocalDate?) {
        named.update(
            """update invoice set base_amount = :b, discount_amount = :d, discount_note = :n, period_start = :ps, period_end = :pe, updated_at = now()
                where id = :id""",
            beP("id" to invoiceId, "b" to baseAmount, "d" to discountAmount, "n" to discountNote, "ps" to beSqlDate(periodStart), "pe" to beSqlDate(periodEnd)),
        )
    }
}

// ───────── PAY-007 ─────────
@Component
class RefundJdbcAdapter(jdbc: JdbcTemplate) : RefundPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    private companion object {
        const val SELECT = """
            select r.*, i.title as invoice_title, s.name as student_name, u.name as created_by_name
              from refund_record r
              join invoice i on i.id = r.invoice_id
              join student s on s.id = r.student_id
              join app_user u on u.id = r.created_by
        """
        val MAPPER = RowMapper<RefundView> { rs, _ ->
            RefundView(
                rs.beId("id")!!, rs.beId("invoice_id")!!, rs.getString("invoice_title"), rs.beId("student_id")!!, rs.getString("student_name"),
                RefundMethod.valueOf(rs.getString("method")), rs.beDate("withdraw_date")!!, rs.beDate("period_start")!!, rs.beDate("period_end")!!,
                rs.getInt("paid_amount"), rs.getInt("calculated_amount"), rs.getInt("refund_amount"), rs.beDate("refunded_on")!!,
                PayMethod.valueOf(rs.getString("pay_method")), rs.getString("note"), rs.getString("created_by_name"), rs.beTs("created_at")!!,
            )
        }
    }

    override fun invoice(id: UUID): RefundInvoice? =
        named.query(
            """select i.id, i.institution_id, i.student_id, s.name as student_name, s.status as student_status, i.title, i.amount, i.status,
                      i.due_date, i.period_start, i.period_end, exists (select 1 from refund_record r where r.invoice_id = i.id) as refunded
                 from invoice i join student s on s.id = i.student_id where i.id = :id""",
            beP("id" to id),
        ) { rs, _ ->
            RefundInvoice(
                rs.beId("id")!!, rs.beId("institution_id")!!, rs.beId("student_id")!!, rs.getString("student_name"), rs.getString("title"),
                rs.getInt("amount"), rs.getString("status"), rs.beDate("due_date")!!, rs.beDate("period_start"), rs.beDate("period_end"),
                rs.getBoolean("refunded"), rs.getString("student_status") == "WITHDRAWN",
            )
        }.firstOrNull()

    override fun insert(
        institutionId: InstitutionId, invoice: RefundInvoice, method: RefundMethod, withdrawDate: LocalDate, periodStart: LocalDate, periodEnd: LocalDate,
        calculated: Int, refund: Int, refundedOn: LocalDate, payMethod: PayMethod, note: String?, by: UserId, now: Instant,
    ): UUID {
        val id = Uuid7.next()
        named.update(
            """insert into refund_record (id, institution_id, invoice_id, student_id, method, withdraw_date, period_start, period_end,
                   paid_amount, calculated_amount, refund_amount, refunded_on, pay_method, note, created_by, created_at)
               values (:id, :i, :inv, :s, :m, :w, :ps, :pe, :paid, :calc, :ref, :on, :pm, :note, :by, :now)""",
            beP("id" to id, "i" to institutionId.value, "inv" to invoice.invoiceId, "s" to invoice.studentId, "m" to method.name,
                "w" to beSqlDate(withdrawDate), "ps" to beSqlDate(periodStart), "pe" to beSqlDate(periodEnd), "paid" to invoice.amount,
                "calc" to calculated, "ref" to refund, "on" to beSqlDate(refundedOn), "pm" to payMethod.name, "note" to note, "by" to by.value, "now" to beTime(now)),
        )
        return id
    }

    override fun find(id: UUID): RefundView? = named.query("$SELECT where r.id = :id", beP("id" to id), MAPPER).firstOrNull()

    override fun list(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<RefundView> =
        named.query(
            "$SELECT where r.institution_id = :i and r.refunded_on between :f and :t order by r.refunded_on desc, r.created_at desc",
            beP("i" to institutionId.value, "f" to beSqlDate(from), "t" to beSqlDate(to)), MAPPER,
        )
}

// ───────── SET-006 데이터 내보내기 ─────────
@Component
class DataExportJdbcAdapter(jdbc: JdbcTemplate, private val crypto: FieldCrypto) : DataExportPort {
    private val named = NamedParameterJdbcTemplate(jdbc)
    private val zone = ZoneId.of("Asia/Seoul")
    private val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val days = listOf("월", "화", "수", "목", "금", "토", "일")

    private fun ts(rs: ResultSet, col: String) = rs.beTs(col)?.let { dt.format(it.atZone(zone)) }.orEmpty()
    private fun d(rs: ResultSet, col: String) = rs.beDate(col)?.toString().orEmpty()
    private fun s(rs: ResultSet, col: String) = rs.getString(col).orEmpty()
    private fun yn(rs: ResultSet, col: String) = if (rs.getBoolean(col)) "Y" else ""
    private fun dec(v: String?) = v?.let { runCatching { crypto.decrypt(it) }.getOrDefault("(복호화 실패)") }.orEmpty()

    private fun sheet(name: String, headers: List<String>, sql: String, p: MapSqlParameterSource, row: (ResultSet) -> List<String>) =
        ExportSheet(name, headers, named.query(sql, p) { rs, _ -> row(rs) })

    override fun institutionName(institutionId: InstitutionId): String =
        named.queryForObject("select name from institution where id = :i", beP("i" to institutionId.value), String::class.java) ?: "기관"

    override fun sheets(institutionId: InstitutionId, from: LocalDate, to: LocalDate): List<ExportSheet> {
        val p = beP("i" to institutionId.value, "f" to beSqlDate(from), "t" to beSqlDate(to))
        val pAt = beP(
            "i" to institutionId.value,
            "fa" to beTime(from.atStartOfDay(zone).toInstant()), "ta" to beTime(to.plusDays(1).atStartOfDay(zone).toInstant()),
        )
        return listOf(
            sheet(
                "원생", listOf("원생ID", "이름", "생년월일", "학년", "상태", "현재 반", "메모", "등록일"),
                """select s.id, s.name, s.birth_enc, s.grade, s.status, s.memo, s.created_at,
                          (select string_agg(c.name, ', ' order by c.name) from enrollment e join classroom c on c.id = e.classroom_id
                            where e.student_id = s.id and e.to_date is null) as classes
                     from student s where s.institution_id = :i order by s.name""", p,
            ) { rs -> listOf(s(rs, "id"), s(rs, "name"), dec(rs.getString("birth_enc")), s(rs, "grade"), s(rs, "status"), s(rs, "classes"), s(rs, "memo"), ts(rs, "created_at")) },
            sheet(
                "보호자", listOf("원생ID", "원생", "관계", "전화번호", "대표 보호자", "앱 연결"),
                """select g.student_id, s.name, g.relation, g.phone_enc, g.is_primary, g.link_status
                     from guardian g join student s on s.id = g.student_id where g.institution_id = :i order by s.name, g.is_primary desc""", p,
            ) { rs -> listOf(s(rs, "student_id"), s(rs, "name"), s(rs, "relation"), dec(rs.getString("phone_enc")), yn(rs, "is_primary"), s(rs, "link_status")) },
            sheet(
                "반", listOf("반ID", "반", "요일", "시작", "종료", "정원", "담당 교사", "삭제일"),
                """select c.id, c.name, c.days_mask, to_char(c.start_time, 'HH24:MI') as st, to_char(c.end_time, 'HH24:MI') as et, c.capacity, c.deleted_at,
                          (select string_agg(u.name, ', ' order by u.name) from classroom_teacher ct join app_user u on u.id = ct.user_id where ct.classroom_id = c.id) as teachers
                     from classroom c where c.institution_id = :i order by c.deleted_at nulls first, c.name""", p,
            ) { rs ->
                val mask = rs.getInt("days_mask")
                listOf(s(rs, "id"), s(rs, "name"), days.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(""), s(rs, "st"), s(rs, "et"),
                    rs.getInt("capacity").toString(), s(rs, "teachers"), ts(rs, "deleted_at"))
            },
            sheet(
                "반 배정", listOf("원생ID", "원생", "반", "시작일", "종료일"),
                """select e.student_id, s.name, c.name as cname, e.from_date, e.to_date
                     from enrollment e join student s on s.id = e.student_id join classroom c on c.id = e.classroom_id
                    where s.institution_id = :i order by s.name, e.from_date""", p,
            ) { rs -> listOf(s(rs, "student_id"), s(rs, "name"), s(rs, "cname"), d(rs, "from_date"), d(rs, "to_date")) },
            sheet(
                "출결", listOf("날짜", "원생ID", "원생", "반", "상태", "지각", "조퇴", "등원", "하원"),
                """select ad.date, ad.student_id, s.name, c.name as cname, ad.status, ad.is_late, ad.is_early_leave, ad.check_in_at, ad.check_out_at
                     from attendance_day ad join student s on s.id = ad.student_id join classroom c on c.id = ad.classroom_id
                    where ad.institution_id = :i and ad.date between :f and :t order by ad.date, c.name, s.name""", p,
            ) { rs -> listOf(d(rs, "date"), s(rs, "student_id"), s(rs, "name"), s(rs, "cname"), s(rs, "status"), yn(rs, "is_late"), yn(rs, "is_early_leave"), ts(rs, "check_in_at"), ts(rs, "check_out_at")) },
            sheet(
                "알림장·공지", listOf("발송", "종류", "제목", "작성자", "대상(원생)", "열람(원생)", "본문"),
                """select n.sent_at, n.kind, n.title, u.name as author, n.body,
                          (select count(distinct r.student_id) from notice_recipient r where r.notice_id = n.id) as targets,
                          (select count(distinct r.student_id) from notice_recipient r where r.notice_id = n.id and r.read_at is not null) as reads
                     from notice n join app_user u on u.id = n.author_id
                    where n.institution_id = :i and n.status = 'SENT' and n.sent_at >= :fa and n.sent_at < :ta order by n.sent_at""", pAt,
            ) { rs -> listOf(ts(rs, "sent_at"), s(rs, "kind"), s(rs, "title"), s(rs, "author"), rs.getInt("targets").toString(), rs.getInt("reads").toString(), s(rs, "body").take(32_000)) },
            sheet(
                "청구·수납", listOf("청구ID", "원생", "제목", "할인 전", "할인", "할인 내역", "청구액", "납부기한", "교습 시작", "교습 종료", "상태", "납부일", "결제수단", "메모"),
                """select i.id, s.name, i.title, i.base_amount, i.discount_amount, i.discount_note, i.amount, i.due_date, i.period_start, i.period_end,
                          i.status, i.paid_on, i.pay_method, i.memo
                     from invoice i join student s on s.id = i.student_id where i.institution_id = :i order by i.due_date, s.name""", p,
            ) { rs ->
                listOf(s(rs, "id"), s(rs, "name"), s(rs, "title"), rs.getObject("base_amount")?.toString().orEmpty(), rs.getInt("discount_amount").toString(),
                    s(rs, "discount_note"), rs.getInt("amount").toString(), d(rs, "due_date"), d(rs, "period_start"), d(rs, "period_end"),
                    s(rs, "status"), d(rs, "paid_on"), s(rs, "pay_method"), s(rs, "memo"))
            },
            sheet(
                "환불", listOf("청구ID", "원생", "계산 방식", "퇴원일", "납부액", "계산액", "환불액", "환불일", "환불 수단", "메모"),
                """select r.invoice_id, s.name, r.method, r.withdraw_date, r.paid_amount, r.calculated_amount, r.refund_amount, r.refunded_on, r.pay_method, r.note
                     from refund_record r join student s on s.id = r.student_id where r.institution_id = :i order by r.refunded_on""", p,
            ) { rs ->
                listOf(s(rs, "invoice_id"), s(rs, "name"), s(rs, "method"), d(rs, "withdraw_date"), rs.getInt("paid_amount").toString(),
                    rs.getInt("calculated_amount").toString(), rs.getInt("refund_amount").toString(), d(rs, "refunded_on"), s(rs, "pay_method"), s(rs, "note"))
            },
            sheet(
                "상담", listOf("일시", "교사", "원생", "방식", "상태", "주제"),
                """select cs.starts_at, t.name as teacher, s.name as student, cs.mode, cs.status, cs.topic
                     from consult_slot cs join app_user t on t.id = cs.teacher_id left join student s on s.id = cs.student_id
                    where cs.institution_id = :i and cs.status in ('BOOKED', 'DONE') order by cs.starts_at""", p,
            ) { rs -> listOf(ts(rs, "starts_at"), s(rs, "teacher"), s(rs, "student"), s(rs, "mode"), s(rs, "status"), s(rs, "topic")) },
        )
    }
}

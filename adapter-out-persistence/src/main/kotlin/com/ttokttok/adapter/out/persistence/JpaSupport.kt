package com.ttokttok.adapter.out.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.persistence.Tuple
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/*
 * JPA 공통 지원.
 *
 * 쓰기·단건 조회는 엔티티 + Spring Data Repository(@Query) 로 한다.
 * 조건이 그때그때 붙는 동적 조회와 PostgreSQL 전용 집계(filter, 날짜 연산, string_agg, with 절 등)는
 * Repository 의 정적 @Query 로 표현할 수 없어서 [JpaQueries] 로 EntityManager 에 JPQL/네이티브 쿼리를 만들어 실행한다.
 */

/** 네이티브 쿼리에서 PostgreSQL 이 타입을 추론할 수 없는 null 파라미터 (예: `cast(:x as uuid) is null`) */
class TypedNull(val type: Class<*>)

/** null 이면 타입 정보를 담은 [TypedNull] 로 바꾼다 */
inline fun <reified T : Any> typed(value: T?): Any = value ?: TypedNull(T::class.java)

@Component
class JpaQueries(private val em: EntityManager) {

    /** 네이티브 SQL 조회 → 컬럼 별칭으로 읽는 [Row] 목록 */
    fun rows(sql: String, params: Map<String, Any?> = emptyMap()): List<Row> {
        val q = em.createNativeQuery(sql, Tuple::class.java)
        bind(q, params)
        @Suppress("UNCHECKED_CAST")
        return (q.resultList as List<Tuple>).map(::Row)
    }

    fun row(sql: String, params: Map<String, Any?> = emptyMap()): Row? = rows(sql, params).firstOrNull()

    /** 네이티브 SQL 의 첫 컬럼 하나 */
    fun scalar(sql: String, params: Map<String, Any?> = emptyMap()): Any? {
        val q = em.createNativeQuery(sql)
        bind(q, params)
        return q.resultList.firstOrNull()
    }

    fun exists(sql: String, params: Map<String, Any?> = emptyMap()): Boolean = scalar(sql, params) as Boolean? ?: false

    fun count(sql: String, params: Map<String, Any?> = emptyMap()): Long = (scalar(sql, params) as Number?)?.toLong() ?: 0L

    /**
     * 네이티브 insert/update/delete (on conflict 등 PostgreSQL 전용 쓰기).
     * 영속성 컨텍스트를 거치지 않고 DB 를 바로 바꾸므로, 실행 전에 밀린 변경을 내보내고(flush) 실행 후 비운다(clear) —
     * 같은 트랜잭션에서 다시 읽을 때 옛 엔티티 상태가 보이지 않게 (@Modifying(flushAutomatically, clearAutomatically) 와 같음)
     */
    fun update(sql: String, params: Map<String, Any?> = emptyMap()): Int {
        em.flush()
        val q = em.createNativeQuery(sql)
        bind(q, params)
        return q.executeUpdate().also { em.clear() }
    }

    /**
     * 여러 행을 한 문장으로 넣는다 (`insert into t (a, b)` + ` values (…), (…)` + `on conflict …`).
     * null 이 들어갈 수 있는 값은 [typed] 로 감싼다.
     */
    fun insertValues(head: String, rows: List<List<Any?>>, tail: String = "", chunkSize: Int = 200): Int {
        if (rows.isEmpty()) return 0
        em.flush()
        var total = 0
        rows.chunked(chunkSize).forEach { part ->
            val p = mutableMapOf<String, Any?>()
            val values = part.mapIndexed { r, cols ->
                cols.mapIndexed { c, v -> "v${r}_$c".also { p[it] = v }.let { ":$it" } }.joinToString(", ", "(", ")")
            }.joinToString(", ")
            val q = em.createNativeQuery("$head values $values $tail")
            bind(q, p)
            total += q.executeUpdate()
        }
        em.clear()
        return total
    }

    /** JPQL 조회 (결과 타입 지정) */
    fun <T : Any> jpql(jpql: String, type: Class<T>, params: Map<String, Any?> = emptyMap(), limit: Int? = null, offset: Int? = null): List<T> {
        val q = em.createQuery(jpql, type)
        bind(q, params)
        limit?.let { q.maxResults = it }
        offset?.let { q.firstResult = it }
        return q.resultList
    }

    /** JPQL 다중 컬럼 조회 (select a, b.name ... → Array) */
    fun jpqlRows(jpql: String, params: Map<String, Any?> = emptyMap(), limit: Int? = null, offset: Int? = null): List<Array<Any?>> {
        val q = em.createQuery(jpql)
        bind(q, params)
        limit?.let { q.maxResults = it }
        offset?.let { q.firstResult = it }
        return q.resultList.map { r -> if (r is Array<*>) Array<Any?>(r.size) { r[it] } else arrayOf(r) }
    }

    fun jpqlCount(jpql: String, params: Map<String, Any?> = emptyMap()): Long {
        val q = em.createQuery(jpql)
        bind(q, params)
        return (q.singleResult as Number?)?.toLong() ?: 0L
    }

    /** JPQL 일괄 update/delete. [update] 와 같이 flush → 실행 → clear */
    fun jpqlUpdate(jpql: String, params: Map<String, Any?> = emptyMap()): Int {
        em.flush()
        val q = em.createQuery(jpql)
        bind(q, params)
        return q.executeUpdate().also { em.clear() }
    }

    fun flush() = em.flush()

    /** 새로 만드는 행을 바로 persist (Repository.save 는 id 를 직접 정하는 엔티티면 merge 라서 행마다 select 가 먼저 나간다) */
    fun persist(entity: Any) = em.persist(entity)

    private fun bind(q: Query, params: Map<String, Any?>) {
        val hq = q.unwrap(org.hibernate.query.CommonQueryContract::class.java)
        // JPA 는 쿼리에 없는 이름을 바인딩하면 예외 → 공용 파라미터 맵을 여러 쿼리에 쓸 수 있게 쿼리에 있는 것만 넣는다
        val declared = q.parameters.mapNotNull { it.name }.toSet()
        params.filterKeys { it in declared }.forEach { (name, value) ->
            when (value) {
                is TypedNull -> {
                    @Suppress("UNCHECKED_CAST")
                    hq.setParameter(name, null, value.type as Class<Any>)
                }
                is Collection<*> -> hq.setParameterList(name, value)
                is Enum<*> -> q.setParameter(name, value.name)
                else -> q.setParameter(name, value)
            }
        }
    }
}

/** 네이티브 쿼리 한 행. JDBC ResultSet 처럼 컬럼 별칭(대소문자 무시)으로 읽는다. 숫자·불리언의 null 은 JDBC 와 같이 0/false */
class Row(private val tuple: Tuple) {
    private val index: Map<String, Int> =
        tuple.elements.mapIndexed { i, e -> (e.alias ?: "#$i").lowercase() to i }.toMap()

    fun raw(col: String): Any? = tuple.get(index[col.lowercase()] ?: error("조회 결과에 컬럼이 없습니다: $col (있는 컬럼: ${index.keys})"))
    fun has(col: String): Boolean = index.containsKey(col.lowercase())

    fun uuidOrNull(col: String): UUID? = when (val v = raw(col)) {
        null -> null
        is UUID -> v
        else -> UUID.fromString(v.toString())
    }
    fun uuid(col: String): UUID = uuidOrNull(col) ?: error("null 컬럼: $col")

    fun strOrNull(col: String): String? = when (val v = raw(col)) {
        null -> null
        is String -> v
        is Char -> v.toString()
        is CharArray -> String(v)
        else -> v.toString()
    }
    fun str(col: String): String = strOrNull(col) ?: error("null 컬럼: $col")

    fun numOrNull(col: String): Number? = when (val v = raw(col)) {
        null -> null
        is Number -> v
        is String -> v.toBigDecimal()
        else -> error("숫자가 아닌 컬럼: $col (${v::class.java})")
    }
    fun intOrNull(col: String): Int? = numOrNull(col)?.toInt()
    fun int(col: String): Int = intOrNull(col) ?: 0
    fun longOrNull(col: String): Long? = numOrNull(col)?.toLong()
    fun long(col: String): Long = longOrNull(col) ?: 0L
    fun doubleOrNull(col: String): Double? = numOrNull(col)?.toDouble()
    fun double(col: String): Double = doubleOrNull(col) ?: 0.0
    fun decimalOrNull(col: String): BigDecimal? = when (val v = numOrNull(col)) {
        null -> null
        is BigDecimal -> v
        else -> BigDecimal(v.toString())
    }

    fun boolOrNull(col: String): Boolean? = when (val v = raw(col)) {
        null -> null
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> v.toString().equals("true", true) || v.toString() == "t"
    }
    fun bool(col: String): Boolean = boolOrNull(col) ?: false

    fun instantOrNull(col: String): Instant? = toInstant(raw(col))
    fun instant(col: String): Instant = instantOrNull(col) ?: error("null 컬럼: $col")

    fun dateOrNull(col: String): LocalDate? = toLocalDate(raw(col))
    fun date(col: String): LocalDate = dateOrNull(col) ?: error("null 컬럼: $col")

    fun timeOrNull(col: String): LocalTime? = when (val v = raw(col)) {
        null -> null
        is LocalTime -> v
        is java.sql.Time -> v.toLocalTime()
        is OffsetTime -> v.toLocalTime()
        else -> LocalTime.parse(v.toString())
    }
    fun time(col: String): LocalTime = timeOrNull(col) ?: error("null 컬럼: $col")

    /** uuid[] · text[] 같은 배열 컬럼 */
    fun list(col: String): List<Any?> = when (val v = raw(col)) {
        null -> emptyList()
        is java.sql.Array -> (v.array as Array<*>).toList()
        is Array<*> -> v.toList()
        is Collection<*> -> v.toList()
        else -> listOf(v)
    }
    /** string_agg(x, chr(31)) 로 모은 이름 목록 (배열 컬럼도 받는다) */
    fun names(col: String): List<String> = when (val v = raw(col)) {
        null -> emptyList()
        is String -> v.split('\u001F').filter { it.isNotEmpty() }
        else -> strList(col)
    }
    fun uuidList(col: String): List<UUID> = list(col).filterNotNull().map { if (it is UUID) it else UUID.fromString(it.toString()) }
    fun strList(col: String): List<String> = list(col).filterNotNull().map { it.toString() }

    companion object {
        /** timestamptz 결과는 드라이버·Hibernate 설정에 따라 여러 타입으로 올 수 있어 모두 Instant 로 맞춘다 (hibernate.jdbc.time_zone = UTC) */
        fun toInstant(v: Any?): Instant? = when (v) {
            null -> null
            is Instant -> v
            is OffsetDateTime -> v.toInstant()
            is ZonedDateTime -> v.toInstant()
            is java.sql.Timestamp -> v.toInstant()
            is LocalDateTime -> v.toInstant(ZoneOffset.UTC)
            is java.util.Date -> v.toInstant()
            else -> OffsetDateTime.parse(v.toString()).toInstant()
        }

        fun toLocalDate(v: Any?): LocalDate? = when (v) {
            null -> null
            is LocalDate -> v
            is java.sql.Date -> v.toLocalDate()
            is LocalDateTime -> v.toLocalDate()
            is java.sql.Timestamp -> v.toLocalDateTime().toLocalDate()
            is java.util.Date -> java.sql.Date(v.time).toLocalDate()
            else -> LocalDate.parse(v.toString().take(10))
        }
    }
}

/** 파라미터 맵 단축 */
fun params(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> = mutableMapOf(*pairs)

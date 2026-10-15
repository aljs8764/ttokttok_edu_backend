package com.ttokttok.adapter.out.persistence.repository

import com.ttokttok.adapter.out.persistence.entity.ShuttlePositionEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleRiderEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleRiderKey
import com.ttokttok.adapter.out.persistence.entity.ShuttleRouteEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleStopAlertEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleStopAlertKey
import com.ttokttok.adapter.out.persistence.entity.ShuttleStopEntity
import com.ttokttok.adapter.out.persistence.entity.ShuttleTripEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ShuttleRouteJpaRepository : JpaRepository<ShuttleRouteEntity, UUID> {
    @Query("select r from ShuttleRouteEntity r where r.institutionId = :i and (:all = true or r.active = true) order by r.active desc, r.name")
    fun findForInstitution(@Param("i") institutionId: UUID, @Param("all") includeInactive: Boolean): List<ShuttleRouteEntity>
}

interface ShuttleStopJpaRepository : JpaRepository<ShuttleStopEntity, UUID> {
    /** 정류장 + 탑승 원생 수 */
    @Query(
        """select st, (select count(r) from ShuttleRiderEntity r where r.stopId = st.id)
             from ShuttleStopEntity st where st.routeId in (:ids) order by st.routeId, st.seq""",
    )
    fun findWithRiderCount(@Param("ids") routeIds: Collection<UUID>): List<Array<Any?>>

    /** 남길 정류장(ids) 외에는 지운다 (탑승 배정은 DB 의 on delete cascade) */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShuttleStopEntity st where st.routeId = :r and st.id not in (:ids)")
    fun deleteOthers(@Param("r") routeId: UUID, @Param("ids") keepIds: Collection<UUID>): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShuttleStopEntity st set st.seq = :seq, st.name = :name, st.lat = :lat, st.lng = :lng where st.id = :id and st.routeId = :r")
    fun updateStop(
        @Param("id") id: UUID, @Param("r") routeId: UUID, @Param("seq") seq: Int, @Param("name") name: String,
        @Param("lat") lat: Double, @Param("lng") lng: Double,
    ): Int
}

interface ShuttleRiderJpaRepository : JpaRepository<ShuttleRiderEntity, ShuttleRiderKey> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShuttleRiderEntity r where r.routeId = :r")
    fun deleteByRoute(@Param("r") routeId: UUID): Int

    @Query(
        """select distinct g.userId from ShuttleRiderEntity r, GuardianEntity g
            where g.studentId = r.studentId and r.stopId = :s and g.userId is not null and g.linkStatus = 'LINKED'""",
    )
    fun guardiansAtStop(@Param("s") stopId: UUID): List<UUID>

    @Query(
        """select distinct g.userId from ShuttleRiderEntity r, GuardianEntity g
            where g.studentId = r.studentId and r.routeId = :r and g.userId is not null and g.linkStatus = 'LINKED'""",
    )
    fun guardiansOnRoute(@Param("r") routeId: UUID): List<UUID>
}

interface ShuttleTripJpaRepository : JpaRepository<ShuttleTripEntity, UUID> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShuttleTripEntity t set t.status = 'ENDED', t.endedAt = :now where t.id = :id and t.status = 'RUNNING'")
    fun end(@Param("id") id: UUID, @Param("now") now: Instant): Int

    /** 위치가 끊긴 채 남은 운행 정리 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShuttleTripEntity t set t.status = 'ENDED', t.endedAt = :now where t.status = 'RUNNING' and coalesce(t.lastAt, t.startedAt) < :before")
    fun closeIdle(@Param("before") lastBefore: Instant, @Param("now") now: Instant): Int
}

interface ShuttlePositionJpaRepository : JpaRepository<ShuttlePositionEntity, Long> {
    fun findByTripIdOrderByRecordedAt(tripId: UUID, pageable: Pageable): List<ShuttlePositionEntity>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShuttlePositionEntity p where p.receivedAt < :before")
    fun purgeBefore(@Param("before") before: Instant): Int
}

interface ShuttleStopAlertJpaRepository : JpaRepository<ShuttleStopAlertEntity, ShuttleStopAlertKey> {
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into shuttle_stop_alert (trip_id, stop_id, sent_at) values (:t, :s, :now) on conflict do nothing", nativeQuery = true)
    fun insertIfAbsent(@Param("t") tripId: UUID, @Param("s") stopId: UUID, @Param("now") now: Instant): Int
}

-- S5–6 출결 관리·대시보드 (ATT-001~004, DASH-001·002·005)

-- ATT-003 결석 사유
alter table attendance_day add column absence_reason varchar(500);

-- 대시보드·데일리 리포트: 기관 + 날짜 조회
create index ix_attendance_day_inst_date on attendance_day (institution_id, date);
-- DASH-002 실시간 타임라인: 기관 최신순
create index ix_attendance_event_inst_time on attendance_event (institution_id, occurred_at desc);

-- 감사 로그 (SEC-002 선반영): 출결 수동 변경, 출석부 다운로드
create table audit_log (
    id             bigserial primary key,
    institution_id uuid         not null references institution (id),
    actor_id       uuid         not null,
    action         varchar(50)  not null,
    resource       varchar(50)  not null,
    resource_id    varchar(100),
    diff           jsonb        not null default '{}'::jsonb,
    at             timestamptz  not null
);
create index ix_audit_log_inst_at on audit_log (institution_id, at desc);

-- 일별 출결 집계 (23:50 배치) — DASH-001 이력, Phase2 STAT
create table daily_attendance_stat (
    institution_id uuid not null references institution (id),
    classroom_id   uuid not null references classroom (id),
    date           date not null,
    scheduled      int  not null,
    present        int  not null,
    late           int  not null,
    early_leave    int  not null,
    absent         int  not null,
    updated_at     timestamptz not null default now(),
    primary key (classroom_id, date)
);
create index ix_daily_stat_inst_date on daily_attendance_stat (institution_id, date);

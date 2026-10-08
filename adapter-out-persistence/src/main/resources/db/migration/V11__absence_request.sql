-- Phase 2 ② ATT-007 결석/지각/조퇴 사전 신청 (학부모 앱 → 교직원 확인)
create table absence_request (
    id               uuid primary key,
    institution_id   uuid         not null references institution (id),
    student_id       uuid         not null references student (id),
    guardian_user_id uuid         not null references app_user (id),
    date             date         not null,
    kind             varchar(20)  not null check (kind in ('ABSENT', 'LATE', 'EARLY_LEAVE')),
    -- 지각이면 예상 등원, 조퇴면 예상 하원 시각
    expected_time    time,
    reason           varchar(300) not null,
    status           varchar(20)  not null check (status in ('SUBMITTED', 'ACKNOWLEDGED', 'CANCELLED')),
    acknowledged_by  uuid references app_user (id),
    acknowledged_at  timestamptz,
    created_at       timestamptz  not null default now()
);
create index ix_absence_request_inst_date on absence_request (institution_id, date);
create index ix_absence_request_guardian on absence_request (guardian_user_id, date desc);
-- 같은 아이·날짜·종류의 살아있는 신청은 하나
create unique index ux_absence_request_active on absence_request (student_id, date, kind) where status <> 'CANCELLED';

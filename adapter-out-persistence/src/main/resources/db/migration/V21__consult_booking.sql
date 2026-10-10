-- Phase 2 NTC-008 후반: 상담 예약 슬롯 — 교사·원장이 상담 가능 시간을 열고 학부모가 앱에서 예약한다
create table consult_slot (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    teacher_id     uuid         not null references app_user (id),
    starts_at      timestamptz  not null,
    ends_at        timestamptz  not null,
    mode           varchar(20)  not null default 'IN_PERSON' check (mode in ('IN_PERSON', 'PHONE', 'VIDEO')),
    location       varchar(100),
    status         varchar(20)  not null default 'OPEN' check (status in ('OPEN', 'BOOKED', 'CANCELED', 'DONE')),
    student_id     uuid references student (id),
    booked_by      uuid references app_user (id),
    topic          varchar(300),
    booked_at      timestamptz,
    cancel_reason  varchar(200),
    created_by     uuid         not null references app_user (id),
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    check (ends_at > starts_at)
);
create index ix_consult_slot_inst_time on consult_slot (institution_id, starts_at);
create index ix_consult_slot_teacher_time on consult_slot (teacher_id, starts_at);
create index ix_consult_slot_booked_by on consult_slot (booked_by) where booked_by is not null;
-- 같은 교사가 같은 시각에 슬롯을 두 번 열지 않게 (취소된 것은 제외)
create unique index ux_consult_slot_teacher_start on consult_slot (teacher_id, starts_at) where status <> 'CANCELED';

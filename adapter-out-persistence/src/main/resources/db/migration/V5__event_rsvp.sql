-- S9–10 행사 RSVP (EVT-001·003·004·005, PAR-005)

create table school_event (
    id                    uuid primary key,
    institution_id        uuid         not null references institution (id),
    author_id             uuid         not null references app_user (id),
    title                 varchar(100) not null,
    body                  text,
    location              varchar(200),
    starts_at             timestamptz  not null,
    ends_at               timestamptz,
    targets               jsonb        not null,
    rsvp_enabled          boolean      not null,
    rsvp_deadline         timestamptz,
    reminder_hours_before int          not null default 24,
    reminded_at           timestamptz,
    status                varchar(20)  not null,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now()
);
create index ix_event_inst_starts on school_event (institution_id, starts_at desc);
-- 5분 스케줄러: RSVP 진행 중·미독촉
create index ix_event_reminder on school_event (rsvp_deadline)
    where status = 'ACTIVE' and rsvp_enabled and reminded_at is null;

-- 생성 시점 대상 학생 스냅샷 (미응답 = 대상 − 응답)
create table school_event_target (
    event_id   uuid not null references school_event (id),
    student_id uuid not null references student (id),
    primary key (event_id, student_id)
);
create index ix_event_target_student on school_event_target (student_id);

-- 학생 단위 응답 (다자녀면 자녀별). 마감 전까지 덮어쓰기
create table event_response (
    event_id     uuid        not null references school_event (id),
    student_id   uuid        not null references student (id),
    answer       varchar(10) not null,
    reason       varchar(200),
    responded_by uuid        not null references app_user (id),
    responded_at timestamptz not null,
    primary key (event_id, student_id)
);

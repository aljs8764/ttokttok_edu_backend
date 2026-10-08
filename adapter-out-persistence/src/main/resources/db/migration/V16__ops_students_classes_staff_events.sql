-- Phase 2 ③ 원생·학급·일정: STU-009~011, CLS-003·004, STF-004, EVT-002

-- STU-009 건강/알레르기 특이사항 (AES-GCM 암호화 저장)
create table student_health (
    student_id     uuid primary key references student (id),
    allergies_enc  text,
    conditions_enc text,
    notes_enc      text,
    updated_by     uuid        not null references app_user (id),
    updated_at     timestamptz not null default now()
);

-- STU-010 상담일지/메모 (학부모 공개 여부)
create table student_counsel_note (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    student_id     uuid        not null references student (id),
    author_id      uuid        not null references app_user (id),
    occurred_on    date        not null,
    body           text        not null,
    parent_visible boolean     not null default false,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index ix_counsel_student on student_counsel_note (student_id, occurred_on desc);

-- STU-011 태그/그룹
create table student_tag (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    name           varchar(30) not null,
    color          varchar(9),
    created_at     timestamptz not null default now(),
    unique (institution_id, name)
);
create table student_tag_map (
    student_id uuid not null references student (id),
    tag_id     uuid not null references student_tag (id) on delete cascade,
    primary key (student_id, tag_id)
);
create index ix_tag_map_tag on student_tag_map (tag_id);

-- CLS-003 주간 시간표 (반당 여러 칸)
create table classroom_slot (
    id           uuid primary key,
    classroom_id uuid     not null references classroom (id),
    weekday      smallint not null check (weekday between 1 and 7), -- 1=월 … 7=일
    start_time   time     not null,
    end_time     time     not null,
    subject      varchar(50),
    room         varchar(30),
    teacher_id   uuid references app_user (id)
);
create index ix_slot_classroom on classroom_slot (classroom_id);

-- CLS-004 대기자 명단 (보호자 계정이 없는 문의자도 받으므로 연락처만 암호화 저장)
create table classroom_waitlist (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    classroom_id   uuid        not null references classroom (id),
    student_name   varchar(50) not null,
    guardian_name  varchar(50),
    phone_enc      text        not null,
    phone_last4    char(4)     not null,
    memo           varchar(300),
    status         varchar(20) not null default 'WAITING',
    contacted_at   timestamptz,
    created_by     uuid        not null references app_user (id),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index ix_waitlist_class on classroom_waitlist (classroom_id, created_at) where status = 'WAITING';

-- STF-004 교사 출퇴근 체크 (하루 1건)
create table staff_attendance (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    user_id        uuid        not null references app_user (id),
    work_date      date        not null,
    check_in       timestamptz not null,
    check_out      timestamptz,
    unique (institution_id, user_id, work_date)
);

-- EVT-002 반복 행사 템플릿 (날짜를 골라 한 번에 행사로 만든다)
create table event_template (
    id                         uuid primary key,
    institution_id             uuid         not null references institution (id),
    name                       varchar(50)  not null,
    title                      varchar(100) not null,
    body                       text,
    location                   varchar(200),
    start_time                 time         not null,
    duration_minutes           int          not null default 60,
    targets                    jsonb        not null,
    rsvp_enabled               boolean      not null,
    rsvp_deadline_days_before  int,
    reminder_hours_before      int,
    created_by                 uuid         not null references app_user (id),
    created_at                 timestamptz  not null default now(),
    updated_at                 timestamptz  not null default now()
);
create index ix_event_template_inst on event_template (institution_id, name);

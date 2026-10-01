-- 똑똑 S1 Core Loop 스키마
-- 규칙: PK UUID v7(애플리케이션 생성), 기관 범위 테이블은 institution_id 필수,
--      연락처·생년월일은 AES-256-GCM 암호문(*_enc) + 조회용 HMAC(*_hash)

create table institution (
    id                           uuid primary key,
    name                         varchar(100) not null,
    owner_name                   varchar(50)  not null,
    late_threshold_minutes       int          not null default 10,
    early_leave_threshold_minutes int         not null default 10,
    created_at                   timestamptz  not null default now(),
    updated_at                   timestamptz  not null default now()
);

create table app_user (
    id                   uuid primary key,
    name                 varchar(50)  not null,
    email                varchar(255) unique,
    phone_enc            text,
    phone_hash           varchar(64) unique,
    password_hash        varchar(255) not null,
    must_change_password boolean      not null default false,
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now(),
    constraint ck_user_login_id check (email is not null or phone_hash is not null)
);

create table membership (
    id             uuid primary key,
    user_id        uuid        not null references app_user (id),
    institution_id uuid        not null references institution (id),
    role           varchar(20) not null,
    title          varchar(50),
    created_at     timestamptz not null default now(),
    unique (user_id, institution_id)
);

create table classroom (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    name           varchar(50) not null,
    capacity       int         not null,
    days_mask      int         not null, -- 월=1, 화=2, 수=4 ... 일=64
    start_time     time        not null,
    end_time       time        not null,
    deleted_at     timestamptz,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index ix_classroom_institution on classroom (institution_id) where deleted_at is null;

create table classroom_teacher (
    classroom_id uuid not null references classroom (id),
    user_id      uuid not null references app_user (id),
    primary key (classroom_id, user_id)
);

create table student (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    name           varchar(50) not null,
    birth_enc      text        not null,
    grade          varchar(20),
    status         varchar(20) not null,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index ix_student_institution on student (institution_id);

create table enrollment (
    id           bigserial primary key,
    student_id   uuid not null references student (id),
    classroom_id uuid not null references classroom (id),
    from_date    date not null,
    to_date      date
);
create index ix_enrollment_current_class on enrollment (classroom_id) where to_date is null;
create index ix_enrollment_current_student on enrollment (student_id) where to_date is null;

create table guardian (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    student_id     uuid        not null references student (id),
    phone_enc      text        not null,
    phone_hash     varchar(64) not null,
    phone_last4    char(4)     not null,
    relation       varchar(20),
    is_primary     boolean     not null,
    user_id        uuid references app_user (id),
    link_status    varchar(20) not null,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    unique (student_id, phone_hash)
);
create index ix_guardian_phone_hash on guardian (phone_hash);
create index ix_guardian_user on guardian (user_id);

create table destination (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    name           varchar(50) not null,
    type           varchar(20) not null,
    sort_order     int         not null default 0,
    created_at     timestamptz not null default now()
);

create table attendance_day (
    id                  uuid primary key,
    institution_id      uuid        not null references institution (id),
    student_id          uuid        not null references student (id),
    classroom_id        uuid        not null references classroom (id),
    date                date        not null,
    status              varchar(20) not null,
    is_late             boolean     not null default false,
    is_early_leave      boolean     not null default false,
    check_in_at         timestamptz,
    check_out_at        timestamptz,
    next_destination_id uuid references destination (id),
    updated_at          timestamptz not null default now(),
    unique (student_id, classroom_id, date)
);
create index ix_attendance_day_class_date on attendance_day (classroom_id, date);
create index ix_attendance_day_scheduled on attendance_day (date) where status = 'SCHEDULED';

create table attendance_event (
    id                uuid primary key,
    attendance_day_id uuid        not null references attendance_day (id),
    institution_id    uuid        not null references institution (id),
    student_id        uuid        not null references student (id),
    type              varchar(20) not null,
    from_status       varchar(20) not null,
    to_status         varchar(20) not null,
    actor_id          uuid        not null,
    source            varchar(20) not null,
    reason            varchar(500),
    destination_id    uuid references destination (id),
    occurred_at       timestamptz not null,
    server_at         timestamptz not null default now(),
    idempotency_key   varchar(100),
    unique (institution_id, idempotency_key)
);
create index ix_attendance_event_timeline on attendance_event (student_id, occurred_at desc);

create table outbox (
    id              uuid primary key,
    institution_id  uuid         not null,
    event_type      varchar(100) not null,
    payload         jsonb        not null,
    status          varchar(20)  not null default 'PENDING', -- PENDING / DONE / DEAD
    attempts        int          not null default 0,
    next_attempt_at timestamptz  not null default now(),
    last_error      text,
    created_at      timestamptz  not null default now(),
    processed_at    timestamptz
);
create index ix_outbox_pending on outbox (next_attempt_at) where status = 'PENDING';

create table device_token (
    token        varchar(4096) primary key,
    user_id      uuid          not null references app_user (id),
    flavor       varchar(20)   not null,
    platform     varchar(20)   not null,
    last_seen_at timestamptz   not null default now()
);
create index ix_device_token_user on device_token (user_id, flavor);

create table notification_log (
    id              bigserial primary key,
    institution_id  uuid        not null,
    channel         varchar(20) not null,
    template_code   varchar(50) not null,
    recipient_count int         not null,
    status          varchar(20) not null,
    error           text,
    created_at      timestamptz not null
);

-- ShedLock (다중 인스턴스 스케줄 중복 실행 방지)
create table shedlock (
    name       varchar(64)  primary key,
    lock_until timestamp    not null,
    locked_at  timestamp    not null,
    locked_by  varchar(255) not null
);

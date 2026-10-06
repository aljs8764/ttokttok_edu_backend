-- 학생앱 QR 출석 (스펙 7-7): 출석 QR, 기관 위치(지오펜스), 학생 기기, 기기 연결 코드, 스캔 실패 로그

create table checkin_qr (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    name           varchar(50)  not null,
    token          varchar(64)  not null unique,
    active         boolean      not null default true,
    created_at     timestamptz  not null,
    rotated_at     timestamptz
);
create index ix_checkin_qr_inst on checkin_qr (institution_id) where active;

create table institution_geofence (
    institution_id uuid primary key references institution (id),
    latitude       double precision not null,
    longitude      double precision not null,
    radius_m       int              not null,
    updated_at     timestamptz      not null default now()
);

-- 학생은 계정이 없다. 보호자가 연결한 기기 토큰의 SHA-256 만 저장
create table student_device (
    id             uuid primary key,
    student_id     uuid         not null references student (id),
    institution_id uuid         not null references institution (id),
    linked_by      uuid         not null references app_user (id),
    device_name    varchar(50)  not null,
    token_hash     varchar(64)  not null unique,
    created_at     timestamptz  not null,
    last_seen_at   timestamptz,
    revoked_at     timestamptz
);
create index ix_student_device_student on student_device (student_id) where revoked_at is null;

create table student_link_code (
    code           varchar(16)  primary key,
    student_id     uuid         not null references student (id),
    institution_id uuid         not null references institution (id),
    issued_by      uuid         not null references app_user (id),
    expires_at     timestamptz  not null,
    used_at        timestamptz
);
create index ix_student_link_code_expires on student_link_code (expires_at);

-- 위치는 저장하지 않고 반경 밖 거리(m)만 남긴다 (아동 위치정보 최소 수집)
create table qr_scan_log (
    id             bigserial primary key,
    institution_id uuid         not null references institution (id),
    student_id     uuid         not null references student (id),
    qr_id          uuid references checkin_qr (id),
    classroom_id   uuid,
    outcome        varchar(30)  not null,
    distance_m     int,
    at             timestamptz  not null
);
create index ix_qr_scan_log_inst_at on qr_scan_log (institution_id, at desc);

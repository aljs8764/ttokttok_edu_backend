-- S11–12 설정·파일·약관·세션 (SET-001·002·005, STF-002, PAR-003, DASH-003, 공통 파일 업로드, AUTH 토큰 회전)

-- 공통 파일 (S3 직접 업로드, 스펙 8장)
create table stored_file (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    uploader_id    uuid         not null references app_user (id),
    purpose        varchar(30)  not null,
    original_name  varchar(200) not null,
    mime           varchar(100) not null,
    size           bigint       not null,
    storage_key    varchar(300) not null unique,
    status         varchar(20)  not null,
    created_at     timestamptz  not null default now()
);
create index ix_stored_file_inst on stored_file (institution_id, created_at desc);

-- SET-001 기관 정보
alter table institution
    add column address      varchar(200),
    add column phone        varchar(20),
    add column logo_file_id uuid references stored_file (id),
    add column seal_file_id uuid references stored_file (id);

-- SET-002 목적지 수정·삭제 (출결 기록이 참조하므로 소프트 삭제)
alter table destination add column deleted_at timestamptz;

-- ATT-003 결석 증빙
alter table attendance_day add column evidence_file_id uuid references stored_file (id);

-- NTC-001 첨부 (파일 id 목록)
alter table notice add column attachments jsonb not null default '[]'::jsonb;

-- AUTH: refresh 토큰 회전·폐기 (스펙 3장). 재사용 감지 시 같은 family 전체 폐기
create table refresh_token (
    jti         uuid primary key,
    family_id   uuid        not null,
    user_id     uuid        not null references app_user (id),
    remember_me boolean     not null,
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    replaced_by uuid,
    created_at  timestamptz not null default now()
);
create index ix_refresh_token_family on refresh_token (family_id);
create index ix_refresh_token_user on refresh_token (user_id) where revoked_at is null;

-- STF-002 교직원 이메일 초대
create table staff_invitation (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    email          varchar(200) not null,
    name           varchar(50)  not null,
    role           varchar(20)  not null,
    token          varchar(64)  not null unique,
    invited_by     uuid         not null references app_user (id),
    expires_at     timestamptz  not null,
    accepted_at    timestamptz,
    revoked_at     timestamptz,
    created_at     timestamptz  not null default now()
);
create index ix_staff_invitation_inst on staff_invitation (institution_id, created_at desc);

-- SET-005 약관 버전·동의 이력
create table terms (
    id           uuid primary key,
    type         varchar(40)  not null,
    version      int          not null,
    title        varchar(100) not null,
    body         text         not null,
    required     boolean      not null,
    effective_at timestamptz  not null,
    unique (type, version)
);

create table terms_agreement (
    user_id   uuid        not null references app_user (id),
    terms_id  uuid        not null references terms (id),
    agreed_at timestamptz not null,
    ip        varchar(45),
    primary key (user_id, terms_id)
);

-- 초기 약관 v1 (본문은 법무 검토 후 v2 로 교체 — Open Issue 10)
insert into terms (id, type, version, title, body, required, effective_at) values
    ('01920000-0000-7000-8000-000000000001', 'PARENT_SERVICE', 1, '똑똑 서비스 이용약관', '(법무 검토 후 확정)', true, '2026-01-01T00:00:00Z'),
    ('01920000-0000-7000-8000-000000000002', 'PARENT_PRIVACY', 1, '개인정보 수집·이용 동의', '(법무 검토 후 확정)', true, '2026-01-01T00:00:00Z'),
    ('01920000-0000-7000-8000-000000000003', 'CHILD_PRIVACY_GUARDIAN', 1, '만 14세 미만 아동 개인정보 법정대리인 동의', '(법무 검토 후 확정)', true, '2026-01-01T00:00:00Z'),
    ('01920000-0000-7000-8000-000000000004', 'PARENT_MARKETING', 1, '마케팅 정보 수신 동의(선택)', '(법무 검토 후 확정)', false, '2026-01-01T00:00:00Z'),
    ('01920000-0000-7000-8000-000000000005', 'INSTITUTION_SERVICE', 1, '똑똑 기관 이용약관', '(법무 검토 후 확정)', true, '2026-01-01T00:00:00Z'),
    ('01920000-0000-7000-8000-000000000006', 'INSTITUTION_PRIVACY_PROCESSING', 1, '개인정보 처리 위탁 계약', '(법무 검토 후 확정)', true, '2026-01-01T00:00:00Z');

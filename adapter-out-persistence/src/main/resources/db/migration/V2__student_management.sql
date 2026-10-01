-- S3–4: 원생 상세·재원 관리·초대/가입 승인·엑셀 업로드

alter table student add column memo varchar(2000);

-- STU-012 재원 상태 변경 이력 (STAT-006 퇴원 사유 통계의 원천)
create table student_status_history (
    id             bigserial primary key,
    institution_id uuid        not null references institution (id),
    student_id     uuid        not null references student (id),
    from_status    varchar(20) not null,
    to_status      varchar(20) not null,
    reason         varchar(30),
    effective_date date        not null,
    note           varchar(500),
    actor_id       uuid        not null,
    created_at     timestamptz not null
);
create index ix_status_history_student on student_status_history (student_id);
create index ix_status_history_stat on student_status_history (institution_id, to_status, effective_date);

-- STU-005 초대 링크
create table invitation (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    token          varchar(32)  not null unique,
    phone_enc      text         not null,
    phone_hash     varchar(64)  not null,
    invited_by     uuid         not null,
    expires_at     timestamptz  not null,
    created_at     timestamptz  not null default now()
);

-- STU-004 가입 대기자
create table join_request (
    id                 uuid primary key,
    institution_id     uuid        not null references institution (id),
    invitation_id      uuid        not null references invitation (id),
    child_name         varchar(50) not null,
    birth_enc          text        not null,
    guardian_name      varchar(50) not null,
    guardian_phone_enc text        not null,
    relation           varchar(20),
    status             varchar(20) not null,
    classroom_id       uuid references classroom (id),
    decided_by         uuid,
    decided_at         timestamptz,
    reject_reason      varchar(200),
    submitted_at       timestamptz not null
);
create index ix_join_request_inst_status on join_request (institution_id, status);

-- STU-002 엑셀 업로드 검증 결과 (연락처 포함 → 본문 암호화)
create table student_import_job (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    created_by     uuid        not null,
    status         varchar(20) not null,
    total_rows     int         not null,
    body_enc       text        not null,
    created_at     timestamptz not null
);

-- STU-001 이름 검색
create index ix_student_inst_name on student (institution_id, name);
create index ix_guardian_last4 on guardian (institution_id, phone_last4);

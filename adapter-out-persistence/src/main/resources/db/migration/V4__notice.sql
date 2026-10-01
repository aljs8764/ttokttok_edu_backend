-- S7–8 알림장·전체 공지 (NTC-001·002·004·005·006·009, PAR-004)

-- 대상(전체/반/학생)은 notice.targets jsonb 로 둔다: [{"scope":"CLASS","id":"..."}]
-- 대상 조합은 알림장 단위로만 읽고 쓰며, 수신자 조회는 notice_recipient 스냅샷을 쓰므로 별도 테이블이 필요 없다.
create table notice (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    author_id      uuid         not null references app_user (id),
    kind           varchar(20)  not null,
    title          varchar(100) not null,
    body           text         not null,
    pinned         boolean      not null default false,
    targets        jsonb        not null,
    status         varchar(20)  not null,
    scheduled_at   timestamptz  not null,
    sent_at        timestamptz,
    last_resent_at timestamptz,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);
create index ix_notice_inst_list on notice (institution_id, pinned desc, scheduled_at desc);
create index ix_notice_inst_sent on notice (institution_id, sent_at desc) where status = 'SENT';
-- 매분 스케줄러: 발송 대기 건
create index ix_notice_due on notice (scheduled_at) where status = 'SCHEDULED';

-- 발송 시점 수신자 스냅샷 (학생 × 연결된 보호자 계정). 앱 미연결 학생은 guardian_user_id = null 한 행
create table notice_recipient (
    id               bigserial primary key,
    notice_id        uuid        not null references notice (id),
    student_id       uuid        not null references student (id),
    guardian_user_id uuid references app_user (id),
    delivered_at     timestamptz,
    read_at          timestamptz,
    resent_count     int         not null default 0
);
create unique index ux_notice_recipient on notice_recipient (notice_id, student_id, coalesce(guardian_user_id, '00000000-0000-0000-0000-000000000000'));
create index ix_notice_recipient_user on notice_recipient (guardian_user_id, notice_id);

-- Phase 3 P3-⑥ 역할별 대시보드 (DASH-008~023)

-- DASH-015·023 교직원 공지·업무 지시 (학부모에게는 보이지 않음)
create table staff_memo (
    id             uuid primary key,
    institution_id uuid          not null references institution (id),
    author_id      uuid          not null references app_user (id),
    title          varchar(100)  not null,
    body           varchar(2000),
    due_date       date,
    -- true = 기관의 모든 교직원(작성 시점 이후 합류한 교직원 포함), false = staff_memo_target 에 지정한 사람만
    to_all         boolean       not null,
    canceled_at    timestamptz,
    created_at     timestamptz   not null default now()
);
create index ix_staff_memo_inst on staff_memo (institution_id, created_at desc);

create table staff_memo_target (
    memo_id uuid not null references staff_memo (id) on delete cascade,
    user_id uuid not null references app_user (id),
    primary key (memo_id, user_id)
);
create index ix_staff_memo_target_user on staff_memo_target (user_id);

-- 교직원별 확인·완료 (행이 없으면 안 읽음)
create table staff_memo_ack (
    memo_id  uuid        not null references staff_memo (id) on delete cascade,
    user_id  uuid        not null references app_user (id),
    read_at  timestamptz not null,
    done_at  timestamptz,
    primary key (memo_id, user_id)
);

-- DASH-017 위젯 배치 (사용자·대시보드 종류별, 기관과 무관)
create table user_dashboard_layout (
    user_id    uuid        not null references app_user (id),
    kind       varchar(20) not null check (kind in ('OWNER', 'TEACHER')),
    widgets    jsonb       not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, kind)
);

-- 대시보드 집계용 인덱스
create index if not exists ix_conversation_staff_unread on conversation (institution_id, last_message_at) where staff_unread > 0;
create index if not exists ix_invoice_inst_due on invoice (institution_id, due_date);

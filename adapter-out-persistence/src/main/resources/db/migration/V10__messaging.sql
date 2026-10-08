-- Phase 2 ② 소통: NTC-007 알림장 학부모 회신 · NTC-008 1:1 채팅
-- 대화 = (아이, 보호자 계정) 한 쌍. notice_id 가 있으면 그 알림장에 대한 회신 스레드, 없으면 일반 1:1 채팅.
create table conversation (
    id                   uuid primary key,
    institution_id       uuid         not null references institution (id),
    student_id           uuid         not null references student (id),
    guardian_user_id     uuid         not null references app_user (id),
    notice_id            uuid references notice (id),
    last_message_at      timestamptz  not null,
    last_message_preview varchar(100) not null default '',
    -- 상대가 아직 읽지 않은 수: parent_unread = 보호자가 안 읽은 교직원 메시지, staff_unread = 교직원이 안 읽은 보호자 메시지
    parent_unread        int          not null default 0,
    staff_unread         int          not null default 0,
    created_at           timestamptz  not null default now()
);
create unique index ux_conversation on conversation (student_id, guardian_user_id, coalesce(notice_id, '00000000-0000-0000-0000-000000000000'::uuid));
create index ix_conversation_inst on conversation (institution_id, last_message_at desc);
create index ix_conversation_guardian on conversation (guardian_user_id, last_message_at desc);
create index ix_conversation_notice on conversation (notice_id) where notice_id is not null;

create table message (
    id              uuid primary key,
    conversation_id uuid          not null references conversation (id),
    sender_id       uuid          not null references app_user (id),
    sender_side     varchar(10)   not null check (sender_side in ('PARENT', 'STAFF')),
    body            varchar(2000) not null,
    created_at      timestamptz   not null default now()
);
create index ix_message_conversation on message (conversation_id, created_at desc);

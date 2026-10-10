-- Phase 3 P3-① 안전·운영 고도화
-- ATT-010 미등원 초과 경고: 수업 시작 + 기준 시간(분)이 지나도 등원 기록이 없으면 관리자·교사(·학부모)에게 1번 알린다
alter table institution add column missing_alert_minutes int not null default 30
    check (missing_alert_minutes = 0 or missing_alert_minutes between 10 and 180);
alter table institution add column missing_alert_parent boolean not null default true;

create table attendance_missing_alert (
    attendance_day_id         uuid primary key references attendance_day (id),
    institution_id            uuid         not null references institution (id),
    student_id                uuid         not null references student (id),
    classroom_id              uuid         not null references classroom (id),
    -- 다기관 아이: 같은 날 다른 기관에서 마지막으로 하원한 기록 (경고 문구에 쓴다)
    previous_institution_name varchar(100),
    previous_checkout_at      timestamptz,
    notified_parent           boolean      not null,
    sent_at                   timestamptz  not null
);
create index ix_missing_alert_inst on attendance_missing_alert (institution_id, sent_at desc);

-- CLS-005 반별 교재·진도
create table classroom_textbook (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    classroom_id   uuid         not null references classroom (id),
    name           varchar(100) not null,
    publisher      varchar(50),
    memo           varchar(300),
    archived       boolean      not null default false,
    created_at     timestamptz  not null default now()
);
create index ix_textbook_class on classroom_textbook (classroom_id) where not archived;

create table classroom_progress (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    classroom_id   uuid         not null references classroom (id),
    date           date         not null,
    textbook_id    uuid references classroom_textbook (id) on delete set null,
    content        varchar(500) not null,   -- 예: 3단원 분수의 덧셈 p.42~47
    homework       varchar(500),
    author_id      uuid         not null references app_user (id),
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);
create index ix_progress_class_date on classroom_progress (classroom_id, date desc);

-- EVT-009 행사 후기·사진: 앨범을 행사에 연결 + 학부모 후기
alter table album add column event_id uuid references school_event (id);
create index ix_album_event on album (event_id) where event_id is not null;

create table event_review (
    id         uuid primary key,
    event_id   uuid          not null references school_event (id),
    student_id uuid          not null references student (id),
    user_id    uuid          not null references app_user (id),
    body       varchar(1000) not null,
    hidden     boolean       not null default false,   -- 교직원이 숨김
    created_at timestamptz   not null default now(),
    updated_at timestamptz   not null default now(),
    unique (event_id, user_id)
);
create index ix_event_review_event on event_review (event_id, created_at desc);

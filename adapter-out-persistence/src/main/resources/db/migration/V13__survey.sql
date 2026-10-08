-- Phase 2 ② NTC-010 설문조사 (학부모 앱 응답, 관리자 웹 결과)
create table survey (
    id               uuid primary key,
    institution_id   uuid         not null references institution (id),
    author_id        uuid         not null references app_user (id),
    title            varchar(100) not null,
    description      varchar(1000),
    status           varchar(10)  not null check (status in ('OPEN', 'CLOSED')),
    closes_at        timestamptz,
    last_reminded_at timestamptz,
    created_at       timestamptz  not null default now()
);
create index ix_survey_inst on survey (institution_id, created_at desc);

create table survey_question (
    id         uuid primary key,
    survey_id  uuid          not null references survey (id),
    position   int           not null,
    type       varchar(10)   not null check (type in ('SINGLE', 'MULTI', 'TEXT', 'RATING')),
    text       varchar(300)  not null,
    options    jsonb         not null default '[]'::jsonb,
    required   boolean       not null default true,
    unique (survey_id, position)
);

-- 발송 시점 수신 원생 스냅샷
create table survey_recipient (
    survey_id  uuid not null references survey (id),
    student_id uuid not null references student (id),
    primary key (survey_id, student_id)
);
create index ix_survey_recipient_student on survey_recipient (student_id);

-- 원생당 응답 1건 (보호자 중 먼저 낸 사람)
create table survey_response (
    survey_id        uuid        not null references survey (id),
    student_id       uuid        not null references student (id),
    guardian_user_id uuid        not null references app_user (id),
    answers          jsonb       not null,
    submitted_at     timestamptz not null,
    primary key (survey_id, student_id)
);

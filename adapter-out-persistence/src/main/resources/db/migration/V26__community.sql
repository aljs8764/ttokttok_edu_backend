-- COM-001~009 익명 커뮤니티 (학생 커뮤니티 COM-010 은 보류)
-- 게시판은 표를 따로 두지 않고 (scope, audience, institution_id) 로 정한다.
--   LOUNGE      : 전국 라운지. audience OWNER(원장) · STAFF(실장·교사) · PARENT(학부모), institution_id 없음
--   INSTITUTION : 우리 기관 게시판. audience STAFF(그 기관 교직원 전원) · PARENT(그 기관 학부모), institution_id 필수
-- 작성자 user_id 는 운영 책임(신고 처리·이용 제한·법적 요청)을 위해 저장하지만 API 응답에는 절대 내보내지 않는다.

create table community_post (
    id             uuid primary key,
    scope          varchar(12)   not null check (scope in ('LOUNGE', 'INSTITUTION')),
    audience       varchar(10)   not null check (audience in ('OWNER', 'STAFF', 'PARENT')),
    institution_id uuid references institution (id),
    author_id      uuid          not null references app_user (id),
    -- 라운지에서만 보이는 작성자 표시: 역할(OWNER·ADMIN·TEACHER·PARENT)과 시·도
    author_role    varchar(10)   not null,
    author_region  varchar(20),
    category       varchar(10)   not null check (category in ('FREE', 'QUESTION', 'INFO', 'WORRY')),
    title          varchar(100)  not null,
    body           varchar(5000) not null,
    like_count     int           not null default 0,
    comment_count  int           not null default 0,
    report_count   int           not null default 0,
    -- REPORTED = 신고 누적 자동 숨김(운영사 검토 대기), MODERATED = 운영사 숨김
    hidden         varchar(10) check (hidden in ('REPORTED', 'MODERATED')),
    deleted_at     timestamptz,
    created_at     timestamptz   not null,
    updated_at     timestamptz   not null,
    check ((scope = 'LOUNGE' and institution_id is null) or (scope = 'INSTITUTION' and institution_id is not null))
);
create index ix_community_post_board on community_post (scope, audience, institution_id, created_at desc) where deleted_at is null;
create index ix_community_post_author on community_post (author_id, created_at desc);

-- 글마다 사람별 고정 익명 번호 (0 = 글쓴이)
create table community_anon (
    post_id uuid not null references community_post (id) on delete cascade,
    user_id uuid not null references app_user (id),
    anon_no int  not null,
    primary key (post_id, user_id),
    unique (post_id, anon_no)
);

create table community_comment (
    id           uuid primary key,
    post_id      uuid          not null references community_post (id) on delete cascade,
    parent_id    uuid references community_comment (id),
    author_id    uuid          not null references app_user (id),
    anon_no      int           not null,
    body         varchar(1000) not null,
    like_count   int           not null default 0,
    report_count int           not null default 0,
    hidden       varchar(10) check (hidden in ('REPORTED', 'MODERATED')),
    deleted_at   timestamptz,
    created_at   timestamptz   not null
);
create index ix_community_comment_post on community_comment (post_id, created_at);
create index ix_community_comment_author on community_comment (author_id, created_at desc);

create table community_like (
    target_type varchar(10) not null check (target_type in ('POST', 'COMMENT')),
    target_id   uuid        not null,
    user_id     uuid        not null references app_user (id),
    created_at  timestamptz not null,
    primary key (target_type, target_id, user_id)
);

create table community_report (
    id          uuid primary key,
    target_type varchar(10)  not null check (target_type in ('POST', 'COMMENT')),
    target_id   uuid         not null,
    reporter_id uuid         not null references app_user (id),
    reason      varchar(20)  not null check (reason in ('ABUSE', 'PRIVACY', 'SPAM', 'OBSCENE', 'OTHER')),
    memo        varchar(300),
    status      varchar(10)  not null default 'OPEN' check (status in ('OPEN', 'RESOLVED')),
    resolution  varchar(20),
    resolved_by uuid references app_user (id),
    resolved_at timestamptz,
    created_at  timestamptz  not null,
    unique (target_type, target_id, reporter_id)
);
create index ix_community_report_open on community_report (status, created_at);

-- COM-008 이용 제한 (작성자 신원은 콘솔에 보이지 않고 글·댓글을 통해서만 제재)
create table community_sanction (
    user_id    uuid primary key references app_user (id),
    until_at   timestamptz,           -- null = 영구
    reason     varchar(200) not null,
    created_by uuid         not null references app_user (id),
    created_at timestamptz  not null
);

-- COM-002 이용 규칙 동의 / COM-009 내 글 확인 시각
create table community_member (
    user_id         uuid primary key references app_user (id),
    rules_agreed_at timestamptz,
    activity_seen_at timestamptz
);

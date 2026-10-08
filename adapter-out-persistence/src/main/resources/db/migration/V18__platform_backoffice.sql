-- Phase 2 ⑤ SaaS 운영 백오피스: SAAS-001 기관 승인·정지, SAAS-002 구독 결제 관리, SAAS-003 바우처,
-- SAAS-004 CS 티켓, SAAS-005 공지/점검, SAAS-007 MRR·Churn·CAC
-- 운영사 계정은 platform_admin 에 user_id 를 직접 넣어 지정한다 (예: insert into platform_admin (user_id) values ('…');)

create table platform_admin (
    user_id    uuid primary key references app_user (id),
    created_at timestamptz not null default now()
);

-- SAAS-001 기관 서비스 상태
alter table institution add column service_status varchar(20) not null default 'ACTIVE' check (service_status in ('PENDING', 'ACTIVE', 'SUSPENDED'));
alter table institution add column status_reason varchar(200);
alter table institution add column status_changed_at timestamptz;

-- SAAS-007 지표용: 유료 전환·해지 시점
alter table institution_subscription add column activated_at timestamptz;
alter table institution_subscription add column canceled_at timestamptz;
update institution_subscription set activated_at = updated_at where status = 'ACTIVE' and activated_at is null;
update institution_subscription set canceled_at = updated_at where status = 'CANCELED' and canceled_at is null;

-- SAAS-003 K-비대면 바우처
create table voucher_registration (
    id             uuid primary key,
    institution_id uuid        not null unique references institution (id),
    voucher_code   varchar(50) not null,
    support_ratio  int         not null check (support_ratio between 0 and 100),
    status         varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'ENDED')),
    started_on     date,
    ended_on       date,
    memo           varchar(300),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create table voucher_settlement (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    period_month   char(7)     not null,                 -- YYYY-MM
    billed_amount  int         not null,
    support_amount int         not null,                 -- 정부 지원분 = 청구액 × 지원비율
    status         varchar(20) not null default 'PENDING' check (status in ('PENDING', 'SETTLED')),
    settled_on     date,
    memo           varchar(300),
    created_at     timestamptz not null default now(),
    unique (institution_id, period_month)
);

-- SAAS-004 CS/문의 티켓
create table support_ticket (
    id              uuid primary key,
    institution_id  uuid         not null references institution (id),
    created_by      uuid         not null references app_user (id),
    subject         varchar(100) not null,
    category        varchar(20)  not null default 'GENERAL' check (category in ('GENERAL', 'BILLING', 'BUG', 'FEATURE')),
    status          varchar(20)  not null default 'OPEN' check (status in ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    assignee_id     uuid references app_user (id),
    last_message_at timestamptz  not null default now(),
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now()
);
create index ix_support_ticket_status on support_ticket (status, last_message_at desc);
create index ix_support_ticket_inst on support_ticket (institution_id, last_message_at desc);

create table support_message (
    id            uuid primary key,
    ticket_id     uuid          not null references support_ticket (id) on delete cascade,
    author_id     uuid          not null references app_user (id),
    from_operator boolean       not null,
    body          varchar(4000) not null,
    created_at    timestamptz   not null default now()
);
create index ix_support_message_ticket on support_message (ticket_id, created_at);

-- SAAS-005 공지/점검
create table platform_notice (
    id         uuid primary key,
    kind       varchar(20)   not null check (kind in ('NOTICE', 'MAINTENANCE')),
    title      varchar(100)  not null,
    body       varchar(2000) not null,
    starts_at  timestamptz   not null,
    ends_at    timestamptz,
    cancelled  boolean       not null default false,
    created_by uuid          not null references app_user (id),
    created_at timestamptz   not null default now()
);

-- SAAS-007 CAC 계산용 월별 마케팅·영업 비용 (운영사가 입력)
create table platform_marketing_cost (
    month      char(7) primary key,
    amount     bigint      not null check (amount >= 0),
    updated_by uuid        not null references app_user (id),
    updated_at timestamptz not null default now()
);

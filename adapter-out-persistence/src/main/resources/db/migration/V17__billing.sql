-- Phase 2 ④ 결제·구독: PAY-001~003 청구·수납·미납 알림, SET-003 SaaS 구독, DASH-007 위젯
-- PG 연동 전이라 수납은 원장이 수동 체크하고, 구독 결제 이력은 운영사(⑤)가 기록한다. 카드는 표시용(브랜드·끝 4자리)만 저장한다.

-- 학부모 알림 카테고리 추가 (SET-004)
alter table notification_setting drop constraint if exists notification_setting_category_check;
alter table notification_setting add constraint notification_setting_category_check
    check (category in ('ATTENDANCE', 'NOTICE', 'EVENT', 'CHAT', 'SURVEY', 'ALBUM', 'ABSENCE', 'PAYMENT'));

-- PAY-001 청구서 (원생 1명 × 1건)
create table invoice (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    student_id     uuid         not null references student (id),
    title          varchar(100) not null,
    amount         int          not null check (amount >= 0),
    due_date       date         not null,
    memo           varchar(300),
    status         varchar(20)  not null default 'UNPAID' check (status in ('UNPAID', 'PAID', 'CANCELED')),
    paid_on        date,
    pay_method     varchar(20)  check (pay_method in ('CASH', 'TRANSFER', 'CARD', 'OTHER')),
    paid_note      varchar(200),
    marked_by      uuid references app_user (id),
    sent_at        timestamptz,
    created_by     uuid         not null references app_user (id),
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);
create index ix_invoice_institution_due on invoice (institution_id, due_date desc);
create index ix_invoice_student on invoice (student_id, due_date desc);
create index ix_invoice_unpaid_due on invoice (due_date) where status = 'UNPAID';

-- PAY-003 자동 리마인드 중복 방지 (청구서 × 단계 1회)
create table invoice_reminder_log (
    invoice_id uuid        not null references invoice (id) on delete cascade,
    stage      varchar(20) not null,
    sent_at    timestamptz not null default now(),
    primary key (invoice_id, stage)
);

-- SET-003 요금제 (사업계획서 6장)
create table subscription_plan (
    code          varchar(20) primary key,
    name          varchar(30) not null,
    monthly_price int,                      -- null = 별도 협의
    student_limit int,                      -- null = 제한 없음
    description   varchar(200),
    sort_order    int         not null
);
insert into subscription_plan (code, name, monthly_price, student_limit, description, sort_order) values
    ('TRIAL',      'Free Trial', 0,     null, '전 기능 14일 체험', 1),
    ('BASIC',      'Basic',      49000, 49,   '원생 50명 미만 · 출결·이동 관리 + 기본 알림장', 2),
    ('PRO',        'Pro',        99000, 149,  '원생 150명 미만 · 셔틀 관제 + 알림장/행사 무제한 + 엑셀 다운로드', 3),
    ('ENTERPRISE', 'Enterprise', null,  null, '프랜차이즈 전용 커스터마이징 · API 제공 (별도 협의)', 4);

create table institution_subscription (
    institution_id       uuid primary key references institution (id),
    plan_code            varchar(20) not null references subscription_plan (code),
    status               varchar(20) not null check (status in ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELED')),
    trial_ends_on        date,
    next_billing_date    date,
    cancel_at_period_end boolean     not null default false,
    card_brand           varchar(20),
    card_last4           char(4),
    card_updated_at      timestamptz,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);

-- SET-003 결제 내역 (운영사/PG 가 기록)
create table subscription_payment (
    id             uuid primary key,
    institution_id uuid        not null references institution (id),
    plan_code      varchar(20) not null references subscription_plan (code),
    amount         int         not null,
    status         varchar(20) not null check (status in ('PAID', 'FAILED', 'REFUNDED')),
    period_start   date        not null,
    period_end     date        not null,
    paid_at        timestamptz,
    failure_reason varchar(200),
    pg_transaction_id varchar(100),
    created_at     timestamptz not null default now()
);
create index ix_subscription_payment_inst on subscription_payment (institution_id, created_at desc);

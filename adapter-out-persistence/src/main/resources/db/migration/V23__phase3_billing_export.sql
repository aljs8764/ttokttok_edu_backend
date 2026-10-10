-- Phase 3 P3-② 수납 고도화·데이터 내보내기
-- PAY-006 원비 할인 규칙: 청구서 발행 때 자동 반영 (형제·장기 등록·다과목)
create table discount_rule (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    name           varchar(50)  not null,
    kind           varchar(20)  not null check (kind in ('SIBLING', 'LONG_TERM', 'MULTI_CLASS')),
    -- SIBLING: 몇째부터(2 = 둘째부터), LONG_TERM: 등록 후 몇 개월부터, MULTI_CLASS: 몇 개 반부터
    threshold      int          not null check (threshold between 1 and 120),
    value_type     varchar(10)  not null check (value_type in ('PERCENT', 'AMOUNT')),
    value          int          not null check (value > 0),
    active         boolean      not null default true,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    check (value_type <> 'PERCENT' or value <= 100)
);
create index ix_discount_rule_inst on discount_rule (institution_id) where active;

-- 청구서에 할인 전 금액·할인 내역·교습 기간 (환불 계산 기준)
alter table invoice add column base_amount int;
alter table invoice add column discount_amount int not null default 0;
alter table invoice add column discount_note varchar(300);
alter table invoice add column period_start date;
alter table invoice add column period_end date;
alter table invoice add constraint ck_invoice_period check (period_start is null or period_end is null or period_end >= period_start);

-- PAY-007 중도 퇴원 환불 기록 (송금은 PG 연동 전까지 원장이 직접, 여기는 계산·처리 기록)
create table refund_record (
    id                uuid primary key,
    institution_id    uuid         not null references institution (id),
    invoice_id        uuid         not null unique references invoice (id),
    student_id        uuid         not null references student (id),
    method            varchar(10)  not null check (method in ('LAW', 'DAILY')),
    withdraw_date     date         not null,
    period_start      date         not null,
    period_end        date         not null,
    paid_amount       int          not null,
    calculated_amount int          not null,
    refund_amount     int          not null check (refund_amount >= 0),
    refunded_on       date         not null,
    pay_method        varchar(20)  not null check (pay_method in ('CASH', 'TRANSFER', 'CARD', 'OTHER')),
    note              varchar(300),
    created_by        uuid         not null references app_user (id),
    created_at        timestamptz  not null default now()
);
create index ix_refund_inst on refund_record (institution_id, refunded_on desc);

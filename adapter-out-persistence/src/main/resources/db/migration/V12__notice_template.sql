-- Phase 2 ② NTC-003 알림장 템플릿 라이브러리
create table notice_template (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    kind           varchar(20)  not null,
    name           varchar(50)  not null,
    title          varchar(100) not null,
    body           text         not null,
    created_by     uuid         not null references app_user (id),
    use_count      int          not null default 0,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);
create index ix_notice_template_inst on notice_template (institution_id, name);

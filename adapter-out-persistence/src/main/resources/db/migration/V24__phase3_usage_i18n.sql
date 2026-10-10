-- Phase 3 P3-③ 플랫폼: SAAS-006 사용량·이상 탐지, SET-007 다국어 UI

-- 기관별 시간 단위 API 사용량. 로그인처럼 기관을 모르는 요청은 institution_id = 00000000-0000-0000-0000-000000000000 (플랫폼 전체)
create table api_usage_hourly (
    institution_id uuid        not null,
    hour           timestamptz not null,
    requests       int         not null default 0,
    errors         int         not null default 0,   -- 5xx
    denied         int         not null default 0,   -- 401·403
    throttled      int         not null default 0,   -- 429
    active_users   int         not null default 0,   -- 그 시간 서로 다른 사용자 수 (인스턴스별 최대값, 근사)
    login_success  int         not null default 0,
    login_failed   int         not null default 0,
    primary key (institution_id, hour)
);
create index ix_api_usage_hour on api_usage_hourly (hour);

create table usage_alert (
    id              uuid primary key,
    institution_id  uuid,                         -- null = 플랫폼 전체 (로그인 실패 급증 등)
    kind            varchar(20)  not null check (kind in ('SPIKE', 'ERRORS', 'DENIED', 'THROTTLED', 'LOGIN_FAIL', 'SILENT')),
    hour            timestamptz  not null,
    value           double precision not null,
    baseline        double precision,
    message         varchar(300) not null,
    created_at      timestamptz  not null default now(),
    acknowledged_at timestamptz,
    acknowledged_by uuid references app_user (id)
);
create unique index ux_usage_alert on usage_alert (coalesce(institution_id, '00000000-0000-0000-0000-000000000000'::uuid), kind, hour);
create index ix_usage_alert_open on usage_alert (created_at desc) where acknowledged_at is null;

-- SET-007 사용자 표시 언어
alter table app_user add column locale varchar(10) not null default 'ko' check (locale in ('ko', 'en'));

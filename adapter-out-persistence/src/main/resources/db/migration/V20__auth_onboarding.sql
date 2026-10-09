-- Phase 2 ⑦ 인증·온보딩: AUTH-006 소셜 로그인, AUTH-007 2단계 인증(OTP), AUTH-010 자동 로그아웃, ONB-003 샘플 체험 기관

-- AUTH-007 TOTP (인증 앱 6자리). secret 은 FieldCrypto 로 암호화, 복구 코드는 HMAC 해시만 저장
create table user_otp (
    user_id        uuid primary key references app_user (id),
    secret_enc     text        not null,
    enabled        boolean     not null default false,
    last_step      bigint      not null default 0,     -- 같은 코드 재사용(리플레이) 차단
    recovery_hashes text       not null default '',    -- 쉼표 구분
    enabled_at     timestamptz,
    updated_at     timestamptz not null default now()
);

-- 비밀번호(또는 소셜) 확인 뒤 OTP 를 기다리는 로그인. 5분, 5회
create table login_challenge (
    id          uuid primary key,
    user_id     uuid        not null references app_user (id),
    remember_me boolean     not null default false,
    attempts    int         not null default 0,
    expires_at  timestamptz not null,
    used_at     timestamptz
);
create index ix_login_challenge_expires on login_challenge (expires_at);

-- AUTH-006 소셜 계정 연결 (카카오·네이버)
create table user_social (
    provider         varchar(20)  not null check (provider in ('KAKAO', 'NAVER')),
    provider_user_id varchar(100) not null,
    user_id          uuid         not null references app_user (id),
    email            varchar(255),
    via_signup       boolean      not null default false,  -- 소셜로 가입한 계정의 연결은 해제할 수 없다(비밀번호가 없음)
    linked_at        timestamptz  not null default now(),
    primary key (provider, provider_user_id),
    unique (user_id, provider)
);

-- 연결된 계정이 없는 소셜 로그인 → 앱에서 이름·휴대폰 확인 후 학부모 가입. 30분
create table social_signup (
    id               uuid primary key,
    provider         varchar(20)  not null,
    provider_user_id varchar(100) not null,
    email            varchar(255),
    name             varchar(50),
    phone            varchar(20),
    expires_at       timestamptz  not null,
    used_at          timestamptz
);

-- AUTH-010 관리자 웹 미사용 자동 로그아웃 (분, 0 = 끔)
alter table institution add column session_idle_minutes int not null default 30
    check (session_idle_minutes = 0 or session_idle_minutes between 5 and 480);

-- ONB-003 샘플(체험) 기관 — 운영 지표·운영사 목록에서 구분
alter table institution add column is_sample boolean not null default false;

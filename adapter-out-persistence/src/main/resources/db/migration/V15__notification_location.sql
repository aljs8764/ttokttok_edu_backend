-- Phase 2 ② SET-004 채널별 알림 옵션 · PAR-008 위치공유 동의
-- 행이 없으면 모두 켜짐(기본값)으로 본다
create table notification_setting (
    institution_id   uuid        not null references institution (id),
    category         varchar(20) not null check (category in ('ATTENDANCE', 'NOTICE', 'EVENT', 'CHAT', 'SURVEY', 'ALBUM', 'ABSENCE')),
    push_enabled     boolean     not null default true,
    alimtalk_enabled boolean     not null default true,
    updated_at       timestamptz not null default now(),
    primary key (institution_id, category)
);

-- 아이 단위 위치 정보 제공 동의 (학생앱 QR 출석의 위치 확인). 행이 없으면 동의로 본다
create table child_location_consent (
    child_id   uuid primary key references child (id),
    granted    boolean     not null,
    updated_by uuid        not null references app_user (id),
    updated_at timestamptz not null default now()
);

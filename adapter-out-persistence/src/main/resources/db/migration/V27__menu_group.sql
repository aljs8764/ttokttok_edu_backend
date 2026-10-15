-- SAAS-008 기관별 메뉴 그룹: 운영사가 그룹마다 끌 메뉴를 고르고 기관을 그룹에 매핑한다.
-- 그룹에 매핑되지 않은 기관은 모든 메뉴를 쓴다. 메뉴 키는 관리자 웹 경로(예: /dashboard).
-- 끈 메뉴만 저장하므로 나중에 새 메뉴가 생기면 모든 그룹에서 켜진 상태로 시작한다.
create table menu_group (
    id          uuid primary key,
    name        varchar(50)  not null unique,
    description varchar(200),
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

create table menu_group_disabled_menu (
    menu_group_id uuid        not null references menu_group (id) on delete cascade,
    menu_key      varchar(60) not null,
    primary key (menu_group_id, menu_key)
);

-- 기관당 그룹 하나. 그룹을 지우면 매핑도 지워져 모든 메뉴로 돌아간다
create table institution_menu_group (
    institution_id uuid primary key references institution (id),
    menu_group_id  uuid not null references menu_group (id) on delete cascade,
    assigned_at    timestamptz not null default now()
);
create index ix_institution_menu_group_group on institution_menu_group (menu_group_id);

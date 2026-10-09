-- Phase 2 ⑥ ATT-009 셔틀버스 실시간 GPS 관제
-- 위치는 차량(운행 중인 기사 폰)의 것만 모은다. 아이의 위치는 수집하지 않는다.
-- 좌표 이력(shuttle_position)은 30일 뒤 스케줄러가 지운다.

create table shuttle_route (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    name           varchar(50)  not null,               -- 예: 2호차 하원
    vehicle_name   varchar(50),
    plate_no       varchar(20),
    driver_name    varchar(50),
    driver_phone   varchar(20),
    active         boolean      not null default true,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);
create index ix_shuttle_route_inst on shuttle_route (institution_id) where active;

create table shuttle_stop (
    id         uuid primary key,
    route_id   uuid             not null references shuttle_route (id) on delete cascade,
    seq        int              not null,
    name       varchar(50)      not null,
    lat        double precision not null check (lat between -90 and 90),
    lng        double precision not null check (lng between -180 and 180)
);
create index ix_shuttle_stop_route on shuttle_stop (route_id, seq);

-- 노선별 탑승 원생과 타고 내리는 정류장
create table shuttle_rider (
    route_id   uuid not null references shuttle_route (id) on delete cascade,
    student_id uuid not null references student (id),
    stop_id    uuid not null references shuttle_stop (id) on delete cascade,
    primary key (route_id, student_id)
);
create index ix_shuttle_rider_student on shuttle_rider (student_id);

create table shuttle_trip (
    id             uuid primary key,
    institution_id uuid             not null references institution (id),
    route_id       uuid             not null references shuttle_route (id),
    driver_id      uuid             not null references app_user (id),
    status         varchar(20)      not null default 'RUNNING' check (status in ('RUNNING', 'ENDED')),
    started_at     timestamptz      not null,
    ended_at       timestamptz,
    last_lat       double precision,
    last_lng       double precision,
    last_speed     double precision,                    -- m/s, 기기가 준 값(없으면 null)
    last_at        timestamptz,
    next_seq       int              not null default 1  -- 아직 지나지 않은 첫 정류장 순번
);
create unique index ux_shuttle_trip_running on shuttle_trip (route_id) where status = 'RUNNING';
create index ix_shuttle_trip_inst on shuttle_trip (institution_id, started_at desc);

create table shuttle_position (
    id          bigserial primary key,
    trip_id     uuid             not null references shuttle_trip (id) on delete cascade,
    lat         double precision not null,
    lng         double precision not null,
    speed       double precision,
    heading     double precision,
    recorded_at timestamptz      not null,
    received_at timestamptz      not null default now()
);
create index ix_shuttle_position_trip on shuttle_position (trip_id, recorded_at);
create index ix_shuttle_position_received on shuttle_position (received_at);

-- 정류장별 "곧 도착" 푸시는 운행당 1번
create table shuttle_stop_alert (
    trip_id uuid        not null references shuttle_trip (id) on delete cascade,
    stop_id uuid        not null references shuttle_stop (id) on delete cascade,
    sent_at timestamptz not null default now(),
    primary key (trip_id, stop_id)
);

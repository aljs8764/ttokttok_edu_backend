-- 다기관 아이 (스펙 7-8): 한 아이가 여러 학원·학교에 다니고, 보호자 한 명이 여러 아이를 둔다.
-- student = 기관이 관리하는 "원생" (기관별 기록), child = 가족이 관리하는 "아이" (기관 무관).
-- 원생 → 아이 연결은 보호자 계정이 연결될 때 자동으로 1:1 로 만들고, 같은 아이인지 합치는 것은 보호자가 확인한다.

-- 기관 종류 (학교는 Phase2 화면, 지금은 구분만)
alter table institution add column type varchar(20) not null default 'ACADEMY'; -- ACADEMY / SCHOOL / DAYCARE / OTHER

create table child (
    id         uuid primary key,
    name       varchar(50) not null,
    birth_enc  text,                       -- AES-256-GCM (원생과 같은 키)
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- 아이를 볼 수 있는 보호자 계정 (엄마·아빠 모두)
create table child_guardian (
    child_id   uuid        not null references child (id),
    user_id    uuid        not null references app_user (id),
    created_at timestamptz not null default now(),
    primary key (child_id, user_id)
);
create index ix_child_guardian_user on child_guardian (user_id);

alter table student add column child_id uuid;

-- 기존 데이터: 보호자 계정이 연결된 원생마다 아이를 하나씩 만든다 (합치기는 보호자가 앱에서)
update student s set child_id = gen_random_uuid()
 where exists (select 1 from guardian g where g.student_id = s.id and g.user_id is not null and g.link_status = 'LINKED');

insert into child (id, name, birth_enc, created_at)
select child_id, name, birth_enc, now() from student where child_id is not null;

insert into child_guardian (child_id, user_id)
select distinct s.child_id, g.user_id
  from student s join guardian g on g.student_id = s.id
 where s.child_id is not null and g.user_id is not null and g.link_status = 'LINKED'
on conflict do nothing;

alter table student add constraint fk_student_child foreign key (child_id) references child (id);
create index ix_student_child on student (child_id) where child_id is not null;

-- 학생앱 기기·연결 코드는 원생(기관 1곳)이 아니라 아이에 묶는다 → 폰 하나로 모든 학원 출석
alter table student_device add column child_id uuid references child (id);
update student_device d set child_id = s.child_id from student s where s.id = d.student_id;
-- 보호자 연결 없이 남은 기기(이론상 없음)는 정리
delete from student_device where child_id is null;
alter table student_device alter column child_id set not null;
alter table student_device drop column student_id;
alter table student_device drop column institution_id;
drop index if exists ix_student_device_student;
create index ix_student_device_child on student_device (child_id) where revoked_at is null;

delete from student_link_code;  -- 10분짜리 1회용이라 버려도 된다
alter table student_link_code drop column student_id;
alter table student_link_code drop column institution_id;
alter table student_link_code add column child_id uuid not null references child (id);

-- 그 기관에 등록되지 않은 아이가 찍은 실패는 원생이 없다 → 원생 대신 아이로 남김
alter table qr_scan_log alter column student_id drop not null;
alter table qr_scan_log add column child_id uuid references child (id);

-- Phase 2 ② NTC-011 사진/영상 앨범
create table album (
    id             uuid primary key,
    institution_id uuid         not null references institution (id),
    author_id      uuid         not null references app_user (id),
    title          varchar(100) not null,
    description    varchar(1000),
    created_at     timestamptz  not null default now()
);
create index ix_album_inst on album (institution_id, created_at desc);

create table album_item (
    album_id uuid not null references album (id) on delete cascade,
    file_id  uuid not null references stored_file (id),
    position int  not null,
    primary key (album_id, position)
);

-- 공개 대상 원생 스냅샷 (반/전체/원생 지정을 펼친 결과)
create table album_recipient (
    album_id   uuid not null references album (id) on delete cascade,
    student_id uuid not null references student (id),
    primary key (album_id, student_id)
);
create index ix_album_recipient_student on album_recipient (student_id);

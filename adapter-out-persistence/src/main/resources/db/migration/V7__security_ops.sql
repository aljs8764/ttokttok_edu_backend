-- 보안·운영: 로그인 실패 잠금(스펙 3장), 감사 로그 조회 인덱스, 미확정 업로드 정리

create table login_attempt (
    user_id      uuid primary key references app_user (id),
    failed_count int         not null,
    locked_until timestamptz,
    updated_at   timestamptz not null default now()
);

create index ix_audit_log_inst_action on audit_log (institution_id, action, at desc);
create index ix_stored_file_pending on stored_file (created_at) where status = 'PENDING';

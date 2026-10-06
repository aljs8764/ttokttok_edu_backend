# 똑똑(Ttok-Ttok) Backend — Phase1 (S1–S12)

> 교사가 [하원(목적지)]을 누르면 학부모가 즉시 푸시를 받고 통합 타임라인에서 확인한다.

Kotlin 2.1 · Spring Boot 3.4 · Java 21 · PostgreSQL 16 · **헥사고날 아키텍처(Ports & Adapters)**

## 모듈 구성

```
domain                    순수 Kotlin. 엔티티·값 객체·도메인 규칙 (Spring/JPA 의존 금지)
application               Inbound Port(유스케이스) + 서비스, Outbound Port 인터페이스, 트랜잭션·권한
adapter-in-web            REST 컨트롤러, Spring Security(JWT), STOMP 인바운드
adapter-in-scheduler      Outbox 폴러, 00:05 예정 생성 / 23:50 결석 확정 (ShedLock)
adapter-out-persistence   JPA·Flyway, AES-256-GCM 필드 암호화, Outbox(SKIP LOCKED), argon2id
adapter-out-notification  FCM 발송 (push.mode=fcm) / 기록용 발송기 (push.mode=log)
adapter-out-realtime      STOMP 브로드캐스트 (트랜잭션 커밋 이후 전송)
adapter-out-storage       Apache POI 엑셀 읽기·쓰기 (원생 일괄 업로드, 이후 출석부)
bootstrap/app-api         web + realtime 조립 → API 서버 (:8080)
bootstrap/app-worker      scheduler + notification 조립 → 백그라운드 워커 (:8081)
```

의존 방향은 항상 `adapter → application → domain`. `bootstrap/app-api`의 `ArchitectureTest`(ArchUnit)가 CI에서 강제한다.

허용 의존 예외: `application`은 `spring-context`(애노테이션), `spring-tx`(@Transactional), `slf4j`만 사용한다.

## Core Loop 흐름

1. 교사앱 `POST /api/v1/attendance/check-out` (Idempotency-Key)
2. `CheckOutService`: 권한(담당 반) → 행 잠금(`FOR UPDATE`) → 도메인 전이(`AttendanceDay.checkOut`) → 이벤트 로그 + **Outbox** 저장 (한 트랜잭션)
3. 커밋 후 STOMP `/topic/inst.{id}`, `/topic/class.{id}` 브로드캐스트
4. app-worker가 Outbox를 `FOR UPDATE SKIP LOCKED`로 가져와 연결된 학부모 기기로 FCM 발송 (실패 시 지수 백오프 3회)
5. 학부모앱 `GET /api/v1/me/timeline` — 소속 전 기관 로그를 시간순 하나로

## 로컬 실행

```bash
docker compose up -d                          # postgres(ttok, ttok_test) + redis
./gradlew :bootstrap:app-api:bootRun          # http://localhost:8080/swagger-ui.html
./gradlew :bootstrap:app-worker:bootRun       # Outbox 처리·배치
./gradlew build                               # 단위 + ArchUnit + Core Loop 통합 테스트
```

통합 테스트는 `TEST_DB_URL`(기본 `jdbc:postgresql://localhost:5432/ttok_test`)의 PostgreSQL이 필요하다.

### 환경 변수 (운영은 Secrets Manager 주입)

| 변수 | 설명 |
| --- | --- |
| `DB_URL` `DB_USERNAME` `DB_PASSWORD` | PostgreSQL |
| `JWT_SECRET` | base64, 32바이트 이상 (HS256) |
| `CRYPTO_ENCRYPTION_KEY` | base64 32바이트 — 연락처·생년월일 AES-256-GCM |
| `CRYPTO_HASH_KEY` | base64 32바이트 — 조회용 HMAC-SHA256 |
| `PUSH_MODE` | `log`(기본, 발송 안 함) / `fcm` |
| `FCM_CREDENTIALS_PATH` | Firebase 서비스 계정 JSON 경로 (`fcm`일 때) |
| `ALIMTALK_MODE` `EMAIL_MODE` | `log`(기본). 대행사·SES 어댑터는 Open Issue #4 확정 후 추가 |
| `JOIN_BASE_URL` `APP_INSTALL_URL` `STAFF_INVITE_BASE_URL` | 초대 링크·앱 설치 안내·교직원 초대 URL |
| `STORAGE_BUCKET` `STORAGE_REGION` `STORAGE_ENDPOINT` | S3 버킷. 로컬은 localstack(`http://localhost:4566`, AWS_ACCESS_KEY_ID/SECRET=test) |

`application.yml`의 기본 키는 **개발 전용**이다. 운영에서 반드시 교체할 것.

## S1 API

| Method | Path | 권한 | 기능 |
| --- | --- | --- | --- |
| POST | /api/v1/auth/institutions | 공개 | 원장 가입(기관+OWNER) |
| POST | /api/v1/auth/parents | 공개 | 학부모 가입, 같은 번호의 보호자 매핑 자동 연결 |
| POST | /api/v1/auth/login · /auth/refresh | 공개 | AUTH-001~003 |
| POST | /api/v1/staff | OWNER | 교사·실장 계정 생성 (임시 PW, 첫 로그인 시 변경 필요 플래그) |
| POST/GET | /api/v1/classes | ADMIN+ / 교직원 | 반 생성·담임 배정, 목록(교사는 담당 반만) |
| POST/GET | /api/v1/destinations | ADMIN+ / 교직원 | 다음 목적지 |
| POST/GET | /api/v1/students | ADMIN+ / 교직원 | 원생 등록(반·보호자), 목록(교사는 담당 반, 마스킹) |
| POST | /api/v1/attendance/check-in · check-out | 담당 교사·ADMIN+ | 원터치 등·하원 |
| GET | /api/v1/attendance/daily?classId&date | 담당 교사·ADMIN+ | 반 출결 현황 |
| GET | /api/v1/me/children · /me/timeline | 학부모 | 자녀 목록, 통합 타임라인(커서 `before`) |
| PUT | /api/v1/me/devices | 로그인 | FCM 토큰 등록 |

## S3–4 API (원생·교직원 관리)

| Method | Path | 권한 | 기능 |
| --- | --- | --- | --- |
| GET | /api/v1/students?classId&status&keyword&page&size | 교직원 | STU-001 검색·페이징(20). keyword = 이름 또는 보호자 번호 뒷 4자리 |
| GET/PATCH | /api/v1/students/{id} | 교직원 / ADMIN+ | STU-006 상세(반 이력·상태 이력·형제), 기본정보·메모 수정 |
| POST | /api/v1/students/{id}/class-move | ADMIN+ | 반 이동 — 이전 소속 마감 + 새 소속 (History) |
| POST | /api/v1/students/{id}/status | ADMIN+ | STU-012 휴원·퇴원(사유 필수)·복귀 |
| POST/DELETE | /api/v1/students/{id}/guardians[/{gid}] | ADMIN+ | 보호자 추가 · STU-007 연결 해제 |
| GET | /api/v1/students/import/template | ADMIN+ | STU-002 템플릿(.xlsx) |
| POST | /api/v1/students/import (multipart `file`) | ADMIN+ | 업로드·검증 → jobId, 오류 행 목록 |
| GET | /api/v1/students/import/{jobId}[/errors] | ADMIN+ | 검증 결과 / 오류 행 엑셀 |
| POST | /api/v1/students/import/{jobId}/commit | ADMIN+ | 정상 행만 확정 등록 (24시간 이내) |
| POST | /api/v1/invitations/parents | ADMIN+ | STU-005 정보 입력 링크 알림톡 (7일 유효) |
| GET/POST | /api/v1/join/{token} | 공개 | 링크 열람 · 자녀 정보 제출 |
| GET | /api/v1/join-requests?status | ADMIN+ | STU-004 가입 대기자 |
| POST | /api/v1/join-requests/{id}/approve · reject | ADMIN+ | 승인(반 배정, 원생 생성) · 거절 — 결과 알림톡 |
| GET/PATCH/DELETE | /api/v1/classes[/{id}] | 교직원 / ADMIN+ | 목록(인원·담당 교사) · 수정 · CLS-002 삭제(0명일 때만) |
| PUT | /api/v1/classes/{id}/teachers | ADMIN+ | STF-003 담당 교사 배정 |
| GET | /api/v1/staff | ADMIN+ | STF-001 교직원 목록 + 담당 반 |
| POST | /api/v1/auth/password/temp | 공개 | AUTH-004 임시 비밀번호 메일 (존재 여부 비노출, 항상 202) |
| PUT | /api/v1/auth/password | 로그인 | AUTH-005 비밀번호 변경 |

## S5–6 API (출결 관리·대시보드)

| Method | Path | 권한 | 기능 |
| --- | --- | --- | --- |
| POST | /api/v1/attendance/bulk | 담당 교사·ADMIN+ | ATT-005~006 오프라인 큐 일괄(최대 100건). 항목별 `{index, ok, attendance, errorCode}`, clientAt 순으로 처리 |
| PATCH | /api/v1/attendance/{dayId}/status | 담당 교사·ADMIN+ | ATT-002 수동 변경 `{status, reason*, isLate?, isEarlyLeave?, source?}` — 이벤트 로그 + 감사 로그, 학부모 푸시 없음 |
| PATCH | /api/v1/attendance/{dayId}/absence-reason | 담당 교사·ADMIN+ | ATT-003 결석 사유 등록·수정(결석일 때만) |
| GET | /api/v1/attendance/report?date&classId? | 교직원 | ATT-001 데일리 리포트 — 반별 집계 + 원생 행(`dayId` 포함) |
| GET | /api/v1/attendance/monthly?month=YYYY-MM&classId | 담당 교사·ADMIN+ | ATT-003 월간 출석부 O/△/X, 출석·지각조퇴·결석 일수, 비고 |
| POST | /api/v1/attendance/export | ADMIN+ | ATT-004 출석부 엑셀 `{classId, month, password?}` — 비밀번호 시 AES(Agile) 암호화, 다운로드 감사 로그 |
| GET | /api/v1/dashboard/today | 교직원 | DASH-001 KPI(등원율·미등원·결석·지각) + DASH-005 위젯(미등원·지각·결석 명단) |
| GET | /api/v1/dashboard/timeline?limit=20 | 교직원 | DASH-002 실시간 타임라인(최신순, 행위자 이름). 갱신은 STOMP `/topic/inst.{id}` 수신 후 재조회 |

교사는 대시보드·리포트를 담당 반 범위로만 본다. 등원율 = (등원+하원) ÷ (출결 행 − 사유 등록된 결석) × 100.
23:50 배치는 미처리 결석 확정 후 `daily_attendance_stat`(반·일 집계)을 갱신한다.

## S7–8 API (알림장·전체 공지)

| Method | Path | 권한 | 기능 |
| --- | --- | --- | --- |
| POST | /api/v1/notices | 교직원 | NTC-001 작성·즉시 발송 / NTC-002 예약(`sendAt`, 30일 이내) / NTC-009 전체 공지(`kind=ANNOUNCEMENT`, 원장·실장) |
| GET | /api/v1/notices?kind&status&page&size | 교직원 | NTC-004 발송 이력 + 열람 통계 (교사는 본인 작성분) |
| GET/PATCH | /api/v1/notices/{id} | 작성자·ADMIN+ | 상세 · 예약 건 수정 |
| POST | /api/v1/notices/{id}/cancel | 작성자·ADMIN+ | 예약 취소 |
| GET | /api/v1/notices/{id}/receipts | 작성자·ADMIN+ | NTC-005 원생별 열람(미열람 먼저)·보호자별 도달/열람 시각 |
| POST | /api/v1/notices/{id}/resend-unread | 작성자·ADMIN+ | NTC-006 미열람 보호자에게만 재푸시 (30분 쿨타임) |
| GET | /api/v1/me/notices?childId&before&limit | 학부모 | PAR-004 알림장함 (전 기관, 커서 = sentAt) |
| GET | /api/v1/me/notices/{id} | 학부모 | 상세 |
| POST | /api/v1/me/notices/{id}/read | 학부모 | 열람 처리 (최초 진입 시각 기록) → STOMP `notice.read` |

대상(`targets`)은 `{scope: ALL|CLASS|STUDENT, id}` 목록. 교사는 담당 반·담당 반 원생만, 전체 대상은 원장·실장만.
발송 시점에 대상을 학생 × 연결 보호자 계정으로 펼쳐 `notice_recipient` 스냅샷을 만든다(이후 반 이동과 무관).
앱 미연결 학생의 보호자에게는 알림톡(`TTOK_NOTICE_NEW`)으로 안내한다. 열람률은 학생 기준(보호자 중 한 명이라도 읽으면 열람).
예약 발송은 app-worker가 매분 `FOR UPDATE SKIP LOCKED`로 처리한다. 대시보드 `noticeReadRate`는 최근 24시간 발송분 평균.

## S9–10 API (행사 RSVP)

| Method | Path | 권한 | 기능 |
| --- | --- | --- | --- |
| POST | /api/v1/events | 교직원 | EVT-001 행사 생성 `{title, startsAt, targets, rsvpEnabled, rsvpDeadline, reminderHoursBefore?}` → 대상 보호자 푸시 |
| GET | /api/v1/events?upcoming&page&size | 교직원 | 행사 목록 + 참석 집계 (교사는 본인 생성분) |
| PATCH | /api/v1/events/{id} | 작성자·ADMIN+ | 수정 (대상·RSVP 여부는 고정, 일정 변경 시 학부모 알림) |
| POST | /api/v1/events/{id}/cancel | 작성자·ADMIN+ | 취소 → 학부모 알림 |
| GET | /api/v1/events/{id}/summary | 작성자·ADMIN+ | EVT-003 참석/불참/미응답 집계 + 명단(미응답 먼저) |
| GET | /api/v1/events/{id}/responses.xlsx | 작성자·ADMIN+ | 명단 엑셀 (학생명·반·응답·사유·응답시각) |
| POST | /api/v1/events/{id}/remind | 작성자·ADMIN+ | EVT-004 미응답 보호자 수동 독촉 (30분 쿨타임) |
| GET | /api/v1/me/events?childId&includePast | 학부모 | PAR-005 RSVP함 (자녀별 응답 상태) |
| PUT | /api/v1/me/events/{id}/response | 학부모 | EVT-005 간편 응답 `{studentId, answer: ATTEND|ABSENT, reason?}` — 마감 후 403, STOMP `event.responded` |

응답 단위는 학생(다자녀면 자녀별)이고 마감 전까지 바꿀 수 있다. 생성 시점 대상 학생을 `school_event_target`에 스냅샷한다.
자동 독촉은 app-worker가 5분마다 "마감 N시간 전(기본 24h) & 미독촉" 행사를 1회 처리한다.

## S11–12 API (설정·파일·약관·세션·스케줄)

| Method | Path | 권한 | 기능 |
| --- | --- | --- | --- |
| GET/PUT | /api/v1/institution | 교직원 / OWNER | SET-001 기관 정보(주소·대표번호·지각/조퇴 기준·로고·직인). 변경 감사 로그 |
| PATCH/DELETE | /api/v1/destinations/{id} | ADMIN+ | SET-002 목적지 수정·소프트 삭제 |
| PUT | /api/v1/destinations/order | ADMIN+ | 표시 순서 변경 `{ids}` |
| POST | /api/v1/files/presign | 교직원 | S3 업로드 URL `{purpose, filename, mime, size}` — jpg/png/heic/pdf, 20MB, 10분 |
| POST | /api/v1/files/{id}/complete | 업로더·ADMIN+ | 업로드 확인(S3 HEAD로 크기 대조) |
| GET | /api/v1/files/{id}/download-url | 교직원 | 5분 만료 다운로드 URL |
| PUT | /api/v1/attendance/{dayId}/evidence | 담당 교사·ADMIN+ | ATT-003 결석 증빙 첨부·해제 `{fileId}` |
| POST/GET/DELETE | /api/v1/staff/invitations[/{id}] | OWNER (목록 ADMIN+) | STF-002 이메일 초대(7일) · 목록 · 취소 |
| GET/POST | /api/v1/staff-invitations/{token}[/accept] | 공개 | 초대 열람 · 수락(신규 계정 생성 또는 기존 계정 비밀번호 확인) → 로그인 토큰 |
| GET | /api/v1/terms?audience=PARENT\|INSTITUTION | 공개 | SET-005 시행 중 약관 |
| GET | /api/v1/me/terms/pending?audience | 로그인 | 미동의 필수 약관(개정 시 재동의) |
| POST | /api/v1/me/terms/agreements | 로그인 | 약관 동의 `{termsIds}` (IP 기록) |
| GET | /api/v1/me/schedule?childId&week | 학부모 | PAR-003 자녀 주간 스케줄(수업·행사·출결 상태, 다기관) |
| GET | /api/v1/dashboard/schedule?days=7 | 교직원 | DASH-003 주요 일정(행사·RSVP 마감·예약 알림장) |
| POST | /api/v1/auth/logout | 공개(refresh 소지) | 이 기기 로그아웃 |
| DELETE | /api/v1/me/sessions | 로그인 | 모든 기기 로그아웃 |
| DELETE | /api/v1/me/devices | 로그인 | FCM 토큰 해제 `{token}` |

refresh 토큰은 쓸 때마다 회전한다(`refresh_token` 테이블). 이미 회전된 토큰이 다시 오면 탈취로 보고 같은 로그인 계열을 모두 폐기한다.
비밀번호 변경·임시 비밀번호 발급 시 기존 세션을 모두 폐기한다. 알림장 생성 API는 `attachments`(파일 id)를 받는다.

## 학생앱 QR 출석 API (스펙 7-7)

학생은 계정이 없다. 보호자가 학부모앱에서 만든 8자리 연결 코드를 학생앱에 넣으면 기기 토큰이 발급되고,
학생 API 는 `X-Device-Token` 헤더로 인증한다(JWT 아님). 스캔은 교사 원터치와 같은 출결 흐름(행 잠금·Outbox·STOMP)을 타고 출처만 `STUDENT_APP`.

| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| GET/POST | /api/v1/checkin-qrs | ADMIN+ | QR-001 출석 QR 목록·만들기(기관당 20개). 응답 `content` 를 QR 로 인쇄 |
| PATCH/DELETE | /api/v1/checkin-qrs/{id} | ADMIN+ | 이름 변경·삭제(비활성) |
| POST | /api/v1/checkin-qrs/{id}/rotate | ADMIN+ | 토큰 재발급 — 이전 인쇄물 즉시 무효 |
| GET | /api/v1/checkin-qrs/failures | ADMIN+ | 최근 7일 스캔 실패(QR_INVALID·OUT_OF_RANGE·NO_CLASS_NOW·LOCATION_REQUIRED·NOT_ENROLLED) |
| GET/PUT | /api/v1/institution/geofence | 교직원 / OWNER | QR-002 기관 위치 `{latitude, longitude, radiusMeters}`(30~1000m). 본문 없이 PUT = 위치 확인 끔 |
| POST | /api/v1/me/children/{id}/device-links | 학부모 | PAR-007 기기 연결 코드(8자리, 10분, 1회) |
| GET/DELETE | /api/v1/me/children/{id}/devices[/{deviceId}] | 학부모 | 연결된 학생 기기 목록·해제 (자녀당 최대 3대, 넘으면 오래된 기기 자동 해제) |
| POST | /api/v1/student/link | 공개(Rate limit) | STD-001 `{code, deviceName}` → `{deviceToken, student, institution}` |
| GET | /api/v1/student/me | 기기 토큰 | STD-002 오늘 수업·출결 |
| POST | /api/v1/student/scan | 기기 토큰 | `{qr, latitude, longitude, accuracy, destinationId?, clientAt}` + Idempotency-Key → `outcome` CHECKED_IN·CHECKED_OUT·CHOOSE_DESTINATION(목적지 목록 포함)·ALREADY_DONE. 실패는 `422 {code: 사유}` |
| POST | /api/v1/student/logout | 기기 토큰 | 이 기기 연결 해제 |

- 위치: (거리 − GPS 정확도[최대 100m]) ≤ 반경. 기관 위치를 설정하지 않으면 위치 확인 생략. 좌표는 저장하지 않고 실패 시 거리(m)만 `qr_scan_log` 에 남긴다.
- 반 고르기: 등원 중인 반 → 하원 / 시작 60분 전~종료 사이 반 → 등원 / 오늘 남은 가장 가까운 반 → 등원.
- 하원 목적지: 기관 목적지가 하나면 자동, 여럿이면 `CHOOSE_DESTINATION` 응답 후 같은 Idempotency-Key 를 새로 만들어 `destinationId` 와 다시 보낸다.
- QR 내용: `${QR_BASE_URL}{token}` (기본 https://ttok.app/qr/). 일반 카메라로 찍으면 앱 안내 페이지.

## 보안·운영

| 항목 | 내용 |
| --- | --- |
| 로그인 잠금 | 계정당 5회 실패 시 10분 잠금(`409 ACCOUNT_LOCKED`), 성공 시 초기화. 남은 횟수는 알려주지 않음(계정 존재 비노출) |
| Rate limit | 로그인·임시 비밀번호·초대 링크 제출·교직원 초대 수락·학생 기기 연결: IP당 분당 10회(`429`). 인스턴스 메모리 기준 |
| 감사 로그 조회 | `GET /api/v1/audit-logs?action&actorId&from&to&page&size` (OWNER, 최신순) |
| 미확정 파일 정리 | 매일 03:30, 업로드 요청 후 24시간 지나도 complete 안 된 파일을 S3·DB에서 삭제 |
| 메일 발송 | `EMAIL_MODE=ses` + `EMAIL_FROM` (SES 도메인 인증 필요). 기본 log |

개인정보 저장 규칙: 초대·가입요청·업로드 작업·Outbox 페이로드의 연락처는 모두 AES-256-GCM 암호문으로 저장한다.

업무 API는 `Authorization: Bearer {access}` + `X-Institution-Id` 헤더를 쓴다. 오류 형식은 `{code, message, details}`.

## S1에서 의도적으로 미룬 것 (TODO)

- 학부모 가입 SMS 본인인증 (Open Issue #1) — 현재는 번호+비밀번호
- Refresh 토큰 회전·폐기 목록(Redis), 로그인 5회 실패 잠금
- PostgreSQL RLS·Hibernate Filter 기반 테넌트 이중 방어 — 현재는 모든 포트 조회에 `institutionId` 필수 + `AccessGuard`
- STOMP Redis 브로커 릴레이(서버 다중화), QueryDSL(조회 전용 Query Port)
- 알림톡 실제 발송 어댑터 (대행사 확정 후, Open Issue 4), Rate limit·KPI 카운터의 Redis 전환
- 엑셀 업로드 중복 검사는 기관 전체 원생을 읽어 비교 — 원생 수천 명 규모가 되면 해시 컬럼 조회로 교체
- S11–12: 학부모 SMS 인증(Open Issue 1), 약관 실제 본문(법무)
- S9–10: 행사 사진·첨부, 대상 변경(추가 초대), 학부모 주간 스케줄(PAR-003)에 행사 노출
- S7–8: 알림장 사진·파일 첨부(S3 저장소), 학부모 회신(댓글), 미열람 재발송 시 알림톡 대체(옵션), 예약 발송 반복 실패 건 격리
- S5–6: 교육청 출석부 실제 양식(Open Issue #5) 확보 후 `PoiAttendanceRegisterAdapter` 레이아웃 교체, 결석 증빙 파일 첨부(S3 저장소), KPI Redis 카운터·공지 열람률(알림장 스프린트), 대형 기관용 비동기 엑셀 job

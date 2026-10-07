# 똑똑 백엔드·관리자 웹·앱 — 인계 메모 (2026-10-07)

> **기준 문서는 백엔드 저장소의 `docs/HANDOFF.md`** (2026-10-07 결정). claude.ai 프로젝트의 `claude/backend/HANDOFF.md` 는 이 날짜 시점의 사본이라 이후 갱신되지 않는다. 진행 상황 갱신은 저장소 쪽에만 한다.

## 백엔드 상태
- 코드: Kotlin 2.1 + Spring Boot 3.4, 헥사고날 멀티모듈. Phase1 전 범위(S1~S12)와 보안·운영 강화, 학생 QR 출석 코드 작성 완료
  - d27130a feat(RT): 학부모 실시간 — 개인 큐 `/user/queue/events` 로 `attendance.changed`{studentId,status}(등·하원·수동 정정)·`notice.new`{noticeId,kind}(발송·재발송)·`event.changed`{eventId,kind}(신규·독촉·취소·변경) 전송. `RealtimePort.userEvents(userIds, payload)` 추가, 수신자 = 연결된 보호자 계정. 워커(소켓 없음)에서는 무시되고 푸시가 대신함. 구독은 `/user/` 면 역할 무관 허용이라 WebSocketConfig 변경 없음
  - 19cc5e4 feat(PUSH·RT): 교사용 푸시·실시간 — 작성자 개인 큐 `/user/queue/events` (notice.read·notice.sent·event.responded), TeacherPushRequested(예약 알림장 발송 완료 NOTICE_SCHEDULED_SENT, 행사 자동 독촉 결과 EVENT_AUTO_REMINDED) → 워커가 AppFlavor.TEACHER 기기로 FCM
  - 9ed25a0 docs: ERD V9 반영 / 5e3b418 feat(7-8): 다기관 아이 (child·child_guardian, 기관 종류, 학생앱 기기 아이 단위)
  - afb96bd docs: ERD (draw.io)
  - 2e5ea59 feat(QR-001·002, PAR-007, STD-001·002): 학생앱 QR 출석 (출석 QR·기관 위치·학생 기기 연결·스캔 등·하원)
  - 7528f77 feat(SEC): 로그인 실패 잠금, Rate limit, 감사 로그 조회, 미확정 파일 정리, SES 메일
  - 03528a1 gitignore 수정 (사용자 커밋)
  - 0b5c900 S11–12 설정·파일·약관·세션·스케줄 / 12b2fd1 S9–10 행사 RSVP / 6453fa8 S7–8 알림장
  - b709841 S5–6 출결·대시보드 / 2160c1a S3–4 원생·교직원 / 91b89b8 S1 Core Loop
- 작업 기준 위치: 사용자 PC `C:\workspaces\ttokttok_edu_backend`
  - git 저장소, origin = github.com/aljs8764/ttokttok_edu_backend, 아직 푸시 안 함 (GitHub 보류)
  - 사용자도 이 폴더에서 직접 커밋함. 반영 전에 로컬 HEAD를 확인할 것
  - 폴더 안 `Claude outputs/` 는 출처 모를 미추적 폴더 — 커밋하지 않고 둠
- 프로젝트 bundle 백업은 하지 않음 (사용자 결정). 코드 원본은 로컬 폴더
- 진행 방식 (사용자 결정): 컴파일·테스트는 실행하지 않고 기능 개발을 계속함. PostgreSQL은 사용자 PC에서 이미 기동 중
- ERD: `docs/erd/ttokttok-erd.drawio` (draw.io — 전체 + 영역별 6페이지, V1~V9 39 테이블)
- 마이그레이션: V1 core loop ~ V7 보안·운영, V8 학생 QR 출석 (checkin_qr, institution_geofence, student_device, student_link_code, qr_scan_log), V9 다기관 아이 (child, child_guardian, student.child_id, institution.type, 기기·연결 코드 child_id 로 이관)
- 진행 현황과 API별 상태는 개발 스펙 문서의 "진행 현황" 섹션과 5장 API 표에 정리. QR 출석 설계는 스펙 7-7
- 교직원 초대 메일 링크가 관리자 웹으로 오게 하려면 `STAFF_INVITE_BASE_URL=http://localhost:3000/invite/` 설정 필요 (기본값은 https://admin.ttok.app/invite/)
- 푸시: 워커 `PUSH_MODE=fcm` + `FCM_CREDENTIALS_PATH=<서비스 계정 키 json>` 이면 실제 발송 (기본 log = 로그만). 학부모 data type: attendance / notice / event
- QR 내용 주소: `QR_BASE_URL` (ttok.links.qr-base-url, 기본 https://ttok.app/qr/). QR 내용 = `{QR_BASE_URL}{token}`

### 다자녀·다기관 (스펙 7-8) 요점
- DB 는 학원별 스키마로 나누지 않고 공유 테이블 + institution_id (사용자 결정 2026-10-07). 격리 강화는 RLS 로 추후
- 원생(student) = 기관별 기록, 아이(child) = 가족 단위. `student.child_id`. 기관은 다른 기관 원생을 못 봄
- 보호자 계정이 원생에 연결될 때 ChildLinker 가 아이를 1:1 생성 + child_guardian 추가 (가입 linkGuardians, StudentCreator.addGuardian). 연결 해제 시 그 아이의 다른 원생에도 연결이 없으면 child_guardian 제거
- GET /me/children 는 아이 단위 + enrollments. 조회 중 아이 없는 원생은 그 자리에서 생성(쓰기 트랜잭션)
- 같은 아이 합치기는 보호자 확인 (merge-suggestions: 이름 공백 무시 + 생일 동일). merge 는 원생·보호자·학생 기기·스캔 로그를 옮기고 source 삭제, split 은 원생 하나를 새 아이로 (기기는 원래 아이에 남음)
- 학부모 목록 필터 childId = 아이 id 또는 원생 id (resolveFilter → Set<StudentId>)
- 학생앱 기기는 아이 단위 → 스캔한 QR 기관의 ACTIVE 원생으로 처리, 없으면 NOT_ENROLLED. QR_INVALID 는 기관을 몰라 로그 안 남김
- StudentPersistenceAdapter.save 는 domain childId 가 null 이면 DB 의 child_id 를 유지 (연결 전 객체로 다시 저장해도 지워지지 않게)
- 교사 여러 기관: 기존 membership 그대로, 교사앱 홈 제목 눌러 전환

### 학생 QR 출석 (스펙 7-7) 요점
- 학생 계정 없음. 보호자가 `POST /me/children/{childId}/device-links` 로 8자리 코드(10분, 1회용, 0/O/1/I/L 제외) 발급 → 학생앱 `POST /student/link` → 기기 토큰(서버엔 SHA-256만). 아이당 활성 기기 최대 3대, 넘으면 오래된 것 해제 (V9 부터 아이 단위)
- 학생앱 API `/api/v1/student/**` 는 JWT 없이 열고 `X-Device-Token` 헤더로 인증 (SecurityConfig permit, /student/link 는 Rate limit 대상)
- 기관 입구용 고정 QR 최대 20개. 재발급하면 이전 인쇄물 무효
- 지오펜스: (거리 − min(정확도, 100m)) ≤ 반경. 반경 30~1000m (기본 150). 설정 안 하면 위치 확인 안 함. 좌표는 저장 안 하고 실패 시 거리만 qr_scan_log
- 수업 선택: 등원 중인 반 → 하원 / 시작 60분 전~종료 사이 반 → 등원 / 다음 수업 → 등원 / 없으면 NO_CLASS_NOW
- 하원 목적지: 하나면 자동, 여러 개면 CHOOSE_DESTINATION → 앱이 destinationId 와 새 Idempotency-Key 로 재요청
- 실패(422): QR_INVALID, NOT_ENROLLED, OUT_OF_RANGE, NO_CLASS_NOW, LOCATION_REQUIRED. 출결 이벤트 출처는 STUDENT_APP (행위자 표시 "학생 QR")

## 관리자 웹 상태 (Next.js, APP 아님) — IA 화면 전부 + 출석 QR 작성 완료
- 위치: 사용자 PC `C:\workspaces\ttokttok_edu_react` (git main, 원격 저장소 없음). package-lock.json 미커밋
  - 7cea345 PUSH·RT 교사 알림장·행사 화면이 개인 큐로 실시간 갱신 (브랜치 claude/friendly-hopper-9o77xy, main 병합 전)
  - 3bfdcec 7-8 기관 종류(학원·학교·어린이집) 설정, QR 실패의 미등록 학생 표시
  - e920417 QR-001·002 출석 QR 만들기·재발급·A4 인쇄, 기관 위치(지오펜스), 스캔 실패 목록 — qrcode 패키지 추가로 **npm install 다시 필요**
  - 1eff178 SET-001·002·005, SEC-002 기관 정보·로고·직인, 하원 목적지, 약관·재동의, 감사 로그, 내 계정
  - 6e0f6d0 NTC-001·004~006, EVT-001·003·004 알림장 작성·예약·첨부·수신 확인·재발송, 행사 RSVP 집계·독촉
  - 76ec41a CLS-001·002, STF-001~003 반 관리·교사 배정·교직원 등록·이메일 초대·초대 수락
  - 77051ce STU-002~008·012 원생 등록·엑셀 일괄 등록·가입 승인·학부모 초대·원생 상세
  - 62bc340 관리자 웹 기반 + Core 화면
- 스택: Next.js 15.5 App Router, React 19, MUI 7.3, MUI X DatePicker 8, TanStack Query 5, @stomp/stompjs 7, dayjs, qrcode 1.5, TypeScript 5.9
- 인증은 BFF 방식
  - 토큰은 httpOnly 쿠키(ttok_at / ttok_rt)에 두고, 모든 API 호출은 `/api/proxy/*`를 거쳐 백엔드 `/api/v1/*`로 감
  - 401이면 refresh 후 1회 재시도. 같은 refresh 토큰의 동시 갱신은 Next 서버에서 하나로 묶어 재사용 탐지를 피함
  - 이 때문에 Next 인스턴스를 여러 대로 늘리면 백엔드에 짧은 재사용 허용 구간이 필요함
  - 실시간(STOMP)용 토큰만 `/api/auth/ws-token`으로 브라우저에 내줌
  - 교직원 초대 수락은 `/invite/[token]`(로그인 전 공개) → `/api/auth/staff-invitations/[token]/accept`가 로그인 쿠키를 심고 초대한 기관을 선택해 둠
- 구현된 화면
  - AUTH-001 로그인 (아이디 저장, 자동 로그인, 기관 선택), AUTH-004 임시 비밀번호, 비밀번호 강제 변경
  - DASH-001·002·003·005 대시보드 (실시간 갱신, 출처 "학생 QR" 표시)
  - ATT-001 데일리 리포트, ATT-002 상태 수동 변경, ATT-003 월간 출석부, ATT-004 엑셀 다운로드
  - STU-001~008·012 원생 목록·등록·엑셀 일괄·가입 승인·학부모 초대·상세(보호자·반 이동·휴원/퇴원)
  - CLS-001·002, STF-001~003 반·교직원
  - NTC-001·004~006 알림장, EVT-001·003·004 행사
  - 설정 (`/settings?tab=`): SET-001 기관 정보, SET-002 하원 목적지, SET-005 약관, SEC-002 감사 로그(원장만), 내 계정. 교사도 볼 수 있음 (탭 안에서 권한별 읽기 전용)
  - QR-001·002 출석 QR (`/qr-codes`, 원장·실장 메뉴): 카드 목록·만들기·이름 변경·재발급(재발급 후 인쇄 창)·삭제·선택 인쇄, 기관 위치 카드(현재 위치로 설정·반경·끄기, 수정은 원장만), 최근 스캔 실패 표
  - 인쇄: `/print/qr?ids=` A4 한 장에 QR 하나, 열리면 자동 window.print()
- 공통 컴포넌트: TargetPicker, AttachmentField(presign → S3 직접 PUT → complete. 버킷 CORS 필요), TermsGate, QrImage(SVG, 오류 정정 M)
- 실시간: 원장·실장은 /topic/inst 의 notice.read·notice.sent·event.responded 로 갱신. 교사는 알림장·행사 화면에서 개인 큐 `/user/queue/events` 구독 (원장·실장이 둘 다 구독하면 중복이라 나눔), 30초 폴링은 2분 안전망으로만 남김 (7cea345, 빌드·연동 미검증)
- 주의: 알림장별 readStats.rate 는 0~1 비율, 대시보드 noticeReadRate 는 % 값
- 빌드 검증은 안 함. 사용자 PC에서 실행: `cp .env.example .env.local && npm install && npm run dev`
- 디자인: Primary #1B2559, Accent #FF6B4A, Surface #F6F7FB. 화면 제목 옆에 IA 메뉴ID 표시
- 전달 방식: 클라우드에서 작성 → tgz로 폴더에 올려 압축 해제 → 사용자 PC에서 jslim 이름으로 커밋 (2026-10-07 세션부터는 PC 연결로 폴더에 바로 쓰고 jslim 이름으로 커밋)

## 앱 상태 (Flutter, 교사·학부모·학생 단일 코드베이스)
- 위치: 사용자 PC `C:\workspaces\ttokttok_edu_app` (git main, 원격 저장소 없음)
  - d051f91 EVT 교사앱 행사 명단 엑셀 — 행사 상세 "명단 엑셀 내보내기" = GET /events/{id}/responses.xlsx 를 임시 폴더에 받아 공유 시트로 열기. path_provider·share_plus 추가 → **flutter pub get 다시**
  - ff199c8 NTC 교사앱 알림장 PDF 첨부 — file_picker 추가 → **flutter pub get 다시**. 첨부 버튼이 사진·PDF 공통(합쳐서 10개, 20MB), 업로드는 사진과 같은 presign→PUT→complete (mime application/pdf, NOTICE_ATTACHMENT 허용 목록에 이미 있음)
  - ace65a2 PAR·RT 학부모앱이 홈에서 `/user/queue/events` 구독 → 타임라인·알림장함·일정 자동 갱신 (0.6초 모아서, 푸시와 같은 갱신 경로). 새 패키지 없음
  - d896074 NTC·PUSH 알림장 목록 필터(종류·상태, 서버 kind·status 파라미터), 교사앱이 개인 큐·푸시를 받아 알림장 목록·상세·행사 집계 자동 갱신, 푸시 탭 → 알림장/행사 상세
  - 3b818e0 NTC 알림장 첨부(PDF 등) 외부 앱으로 열기 — url_launcher 추가 → **flutter pub get 다시**, 길게 누르면 링크 복사
  - 5f882fd ATT-005·006 교사앱 오프라인 큐 (네트워크 없을 때 등·하원을 쌓았다가 POST /attendance/bulk 자동 전송) — 새 패키지 없음
  - bd19639 NTC·EVT 교사앱 알림장·행사 (하단 탭, 작성·예약·수정·취소, 수신 확인·재발송, 행사 RSVP 집계·독촉) — image_picker 추가 → **flutter pub get 다시**
  - debccea 7-8 다자녀·다기관: 아이 단위 자녀 목록·칩, 같은 아이 합치기 배너·나누기·이름, 학생앱 전 기관 출석, 교사 학원 전환
  - 8c51d04 PUSH FCM 푸시 (기기 등록·해제, 포그라운드 알림, 알림 탭 이동) — firebase_core 4, firebase_messaging 16, flutter_local_notifications 22 → **Flutter 3.38.1+ 필요, flutter pub get 다시**
  - 8431b9a STD-001·002, PAR-007 학생앱 QR 출석 flavor, 학부모 학생앱 연결 코드·기기 관리, 카메라·위치 권한 (mobile_scanner 7, geolocator 14)
  - d86944e PAR-001·003·004·005 학부모앱 타임라인·알림장함·주간 일정·행사 응답·가입·약관 재동의, intl 0.20 맞춤
  - bf8aca3 flutter create 로 android·ios 플랫폼 폴더 생성 (AGP 9, Kotlin 2.3, UIScene 템플릿 → 사용자 Flutter 는 3.44+)
  - 0ed3579 Flutter 앱 기반 + 교사앱 Core
  - pubspec.lock 은 미커밋. pub get 성공 후 커밋 필요
  - 빌드 검증 안 함
- 스택: Flutter 3.38.1+ (Dart >=3.10), Riverpod 2.6, Dio 5, go_router 14, flutter_secure_storage 9, stomp_dart_client 2, intl 0.20, uuid, mobile_scanner 7, geolocator 14, image_picker 1.1, file_picker 10, path_provider 2, share_plus 11, url_launcher 6.3, firebase_core 4, firebase_messaging 16, flutter_local_notifications 22
  - 스펙의 Retrofit·freezed 대신 코드 생성 없이 Dio + 손으로 쓴 모델
- flavor: 진입점 3개 `lib/main_teacher.dart`, `lib/main_parent.dart`, `lib/main_student.dart`. 네이티브 productFlavors/iOS scheme(패키지명·아이콘 분리)은 배포 준비 때
- 실행: `flutter run -t lib/main_student.dart --dart-define=API_BASE_URL=http://<PC IP>:8080` (카메라·위치 때문에 실기기 권장)
- 환경: `--dart-define=API_BASE_URL`, `WS_URL` (기본 에뮬레이터용 10.0.2.2:8080)
- 네이티브 설정 (커밋됨): AndroidManifest usesCleartextTraffic·CAMERA·위치·POST_NOTIFICATIONS·FCM 기본 채널 `ttok_alerts`, build.gradle.kts core library desugaring, iOS Info.plist 카메라·앨범·위치 설명·UIBackgroundModes remote-notification
- 인증: 토큰·사용자·선택 기관을 secure storage 에 저장 → 앱 시작 때 복원. access 만료 임박·401 이면 refresh 한 번만 후 재시도
- 교사앱: 로그인, 비밀번호 강제 변경, 기관 선택, 하단 탭 3개 (`TeacherShell`, 탭은 처음 열 때 생성)
  - 출결 탭: 담당 반 목록, 반 출결(ATT-005 원터치 등원, ATT-006 하원+목적지, ATT-002 수동 변경)
    - 낙관적 업데이트 → 실패 시 되돌림. Idempotency-Key + clientAt, 네트워크 오류면 같은 키로 최대 3회. `/topic/class.{반}` STOMP 반영
  - 오프라인 큐 (`attendance_queue.dart`, 출결 탭)
    - 등·하원이 네트워크 오류(재시도 3회 후)면 되돌리지 않고 큐에 저장(shared_preferences `attendance_queue`), 행에 "전송 대기"·반 화면 상단 배너("지금 보내기")
    - 요청의 Idempotency-Key·clientAt 을 큐 항목이 그대로 가져가 POST /attendance/bulk(최대 100건, 서버가 clientAt 순 처리, 항목별 결과) → 응답만 놓친 요청이 이미 처리됐어도 중복 없음
    - 전송 시점: 15초 주기, 앱 복귀, 다른 요청 성공 시, 교사앱 시작(`TeacherShell`). 이 원생의 앞선 건이 큐에 있으면 새 요청도 큐로 (등원 → 하원 순서 보존)
    - 항목 실패: 4xx(401·408·429 제외)·항목별 ok=false 는 버리고 스낵바로 안내(`queueFailuresProvider`), 5xx·네트워크는 유지. 다른 계정·기관의 항목은 보내지 않음
    - 보낸 뒤엔 `classAttendanceProvider` 를 다시 읽어 서버 값으로 맞춤. 반 데이터를 다시 읽을 때도 남은 큐 항목을 위에 얹어 보여 줌
    - 수동 변경(ATT-002)·알림장·행사는 큐 대상 아님 (온라인에서만)
  - 알림장 탭 (`lib/features/teacher/notices_tab.dart`, `notice_form_screen.dart`, `notice_detail_screen.dart`; NTC-001·002·004·005·006·009)
    - 목록 = GET /notices (교사는 본인 작성분, 원장·실장은 전체), 20건 페이지, 당겨서 새로고침, 종류(알림장/공지)·상태(예약/발송/취소) 필터 칩(서버 파라미터). 라우트 `/notices/new`, `/notices/:id`, `/notices/:id/edit`
    - 작성: 알림장/공지(공지·전체 대상은 원장·실장만 — 교사 화면엔 선택지를 숨김), 대상 선택(TargetPicker: 반 칩 + 원생 검색 300ms), 사진·PDF 첨부 합쳐서 최대 10개(AttachmentField: image_picker·file_picker → presign → S3 PUT → complete, 받은 PDF 는 눌러서 열기), 즉시/예약(30일 이내), 공지 상단 고정
    - 예약 건만 수정·취소 (작성자 본인 또는 원장·실장). 수정 화면은 방식(즉시/예약)을 바꾸지 못함
    - 상세: 수신 확인 막대(열람률 rate 0~1), "누가 읽었나요"로 안 읽음 먼저 명단, "안 읽은 분께 다시" = POST /notices/{id}/resend-unread (30분 쿨타임은 receipts.canResendAt 으로 비활성)
    - 생성 POST 는 Idempotency-Key + 네트워크 오류 시 같은 키로 최대 3회
  - 행사 탭 (`events_tab.dart`, `event_form_screen.dart`, `event_detail_screen.dart`; EVT-001·003·004)
    - 목록 = GET /events?upcoming= (예정 / 지난 행사 포함 전환). 라우트 `/events/new`, `/events/:id`, `/events/:id/edit`
    - 만들기: 행사명·시작·종료·장소·안내·대상·RSVP(마감 시각·자동 독촉 6/12/24/48/72시간 전). 시각 검사는 웹과 동일 (생성은 시작·마감이 현재 이후, 마감 ≤ 시작)
    - 수정은 대상·RSVP 사용 여부를 바꾸지 못함 (백엔드 UpdateRequest). 상세/수정은 GET /events/{id}/summary 의 event 를 사용 (단건 조회 API 없음)
    - 상세: 참석/불참/미응답 집계 막대, 필터 칩(미응답 먼저), 수동 독촉 POST /events/{id}/remind (remindedAt + 30분 쿨타임, 마감 후·미응답 0명이면 비활성), 행사 취소
    - 명단 엑셀은 상세의 "명단 엑셀 내보내기"(공유 시트). 응답이 오면 개인 큐 이벤트(event.responded)로 집계 자동 갱신 (TeacherShell 이 0.8초 모아서 다시 읽음), 당겨서 새로고침도 가능
  - 공통 위젯: `core/widgets/attachment_tile.dart`(학부모·교사 알림장 상세 공유), `pill.dart`, `date_time_field.dart`(날짜→시간 선택)
- 학부모앱 (모두 /me/* API, 기관 헤더 없음): 로그인·가입, 하단 탭(타임라인·알림장함·일정/행사·더보기, `parentTabProvider`), 자녀 선택, 약관 재동의 게이트
  - 아이 단위 (`Child{childId, name, enrollments[]}`), 상단 칩·필터는 아이 id. 타임라인 맨 위·더보기에 "같은 아이인가요?" 배너 ("다른 아이예요"는 shared_preferences 에 기억)
  - 더보기 → 아이 카드: 다니는 기관 목록(종류·휴원/퇴원), 기관 나누기, 이름 바꾸기, "학생앱 연결"(PAR-007, 아이 단위)
  - 실시간: 홈에서 `/user/queue/events` 구독(attendance.changed·notice.new·event.changed → 해당 목록 다시 읽기) + 앱 복귀·당겨서 새로고침, 앱이 앞에 있을 때 푸시를 받아도 갱신. 첨부 PDF 는 눌러서 외부 앱으로 열기(실패 시 링크 복사)
- 푸시 (`lib/core/push/push_service.dart`, `lib/app/push_navigation.dart`)
  - Firebase 설정 파일이 있어야 켜짐(없으면 로그만 남기고 끔). 학생앱 제외, 교사앱은 작성자 안내 푸시만 받음 (예약 알림장 발송 완료·행사 자동 독촉 결과; 탭 → 알림장/행사 상세)
  - 로그인·세션 복원 시 알림 권한 요청 → `PUT /me/devices {flavor, platform, token}`, 토큰 갱신 시 재등록, 로그아웃 직전 `DELETE /me/devices`. 세션 만료로 튕기면 해제 못 함 (다음 로그인 때 같은 토큰이 새 계정으로 묶임)
  - 포그라운드: 안드로이드 `ttok_alerts` 로컬 알림, iOS FCM 배너
  - 알림 탭: notice → 알림장 탭 + 상세(열람), event → 일정 탭, attendance → 홈 + 해당 자녀 선택. 종료 상태에서 눌러도 첫 화면 뒤 이동
  - 남은 설정: `flutterfire configure` (google-services.json·GoogleService-Info.plist·Gradle 플러그인), Xcode Push Notifications·Background Modes capability, APNs 키 Firebase 업로드, 실기기 수신 확인
- 학생앱 (`lib/features/student`): 기기 토큰만 복원(로그인 없음) → 미연결이면 `/link` 코드 입력, 홈(오늘 수업·출결, 큰 QR 출석 버튼), `/scan` 카메라 QR + 현재 위치 전송, 목적지 선택 시트, 성공/실패 화면(실패 코드별 안내). 401 이면 토큰 삭제 → 연결 화면

## 남은 일
- 사용자 PC 에서: 관리자 웹 `npm install`·`npm run build`, 앱 `flutter pub get`(pubspec.lock 커밋)·세 flavor 실행 확인, 백엔드 빌드·V8 마이그레이션 적용 확인
- 앱: Firebase 프로젝트 연결·실기기 푸시 확인, 교사앱 알림장·행사 실기기 확인(카메라·앨범 권한, S3 업로드는 버킷 CORS·localstack 필요), 오프라인 큐 실기기 확인(비행기 모드로 등·하원 → 복구 후 자동 전송), PDF 첨부 열기 실기기 확인, 네이티브 flavor(학생앱 별도 스토어 앱)
- 앱 알림장·행사의 PDF 첨부 업로드·행사 명단 엑셀은 완료 (실기기 확인 필요: 파일 선택, 공유 시트)
- 교사용 푸시·실시간은 코드 작성만 (빌드·실기기 미검증): 백엔드 빌드 후 개인 큐 구독(관리자 웹 교사 화면은 구독 코드 추가됨, 앱은 TeacherShell; 교사 로그인 → `/user/queue/events`)과 FCM 교사 기기 수신 확인 필요. 학부모용 `/user/queue/events`(d27130a·ace65a2)도 코드만 — 학부모 로그인 후 소켓 수신 확인 필요
- 관리자 웹: 실제 백엔드와 연동 점검(응답 필드명·권한). 통계(STAT)는 Phase 2
- 백엔드
  - ATT-004 교육청 출석부 실제 양식 반영 (Open Issue 5, 원본 양식 확보 대기)
  - 빌드·테스트 검증, GitHub 푸시 (보류)
  - 미뤄둔 것: 학부모 SMS 인증(Open Issue 1), 알림톡 실제 어댑터(Open Issue 4), 약관 실제 본문(법무), Rate limit·KPI 카운터의 Redis 전환, RLS, 학부모 STOMP 는 완료

## 참고 문서
- 개발 스펙(Claude Docs): https://claude.ai/code/artifact/7c50ae42-0685-42cf-96e1-86323dea730d

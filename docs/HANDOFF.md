# 똑똑 백엔드·관리자 웹·앱 — 인계 메모 (2026-10-12)

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
  - 64e85d1 ONB-001 시작하기 위자드 (`/onboarding`, 원장): 기관 정보 → 하원 목적지 → 첫 반 → 원생·교직원·QR 안내. 반·목적지가 없으면 대시보드에 배너 (브랜치 claude/friendly-hopper-9o77xy)
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

## Phase 2 진행 (2026-10-08~)
- 계획·진행 문서: https://claude.ai/code/artifact/a14878ce-38a5-4fae-9544-cbdf19aa728d (47건 영역별 범위·슬라이스 순서·진행 표, 기준 문서)
- 범위: IA 엑셀 Phase=2 기능 47건 (STAT-007 은 Phase 3, 맞춤 리포트는 STAT-008). 슬라이스 순서 — ① 통계·대시보드 위젯 ② 소통(NTC-003/007/008/010/011, ATT-007, SET-004, PAR-008) ③ 일정(CLS-003/004, STF-004, 행사 캘린더·템플릿) ④ 결제·SaaS 백오피스·설정 ⑤ 온보딩/인증 보강, 셔틀 GPS(ATT-009)
- ① 통계 완료(코드만, 빌드·실행 미검증): 백엔드 9bfaa23 — `/api/v1/stats/{kpi,congestion,classrooms,classrooms/{id}/trend,enrollment,enrollment/trend,report.xlsx}` (원장·실장 전용, 비율은 0~1), `/api/v1/dashboard/widgets/{low-read-notices,congestion}` (직원). 일별 집계는 daily_attendance_stat(오늘 이전)+attendance_day(오늘) 합산. 웹 4bd8a93 — `/stats` 화면(SVG 차트, 외부 차트 라이브러리 없음)·대시보드 DASH-004/006 위젯. 엑셀 리포트(STAT-008)는 시트 3종(기간 KPI·반별 비교·재원 현황) 선택 다운로드 — PDF 는 미구현
- ② 소통 완료(코드만, 빌드·실행 미검증): 백엔드 71b916b·5a37236·87b2bb1·a7894a0·ef0bf7b (V10~V15) — 대화 `/api/v1/(me/)conversations`(알림장 회신 NTC-007 + 1:1 채팅 NTC-008 한 모델, 실시간 `chat.message`), 결석 신청 `/api/v1/(me/)absence-requests`(ATT-007: 기록·확인만, 출결 기록은 안 바꿈), 템플릿 `/api/v1/notice-templates`, 설문 `/api/v1/surveys`·`/me/surveys`(NTC-010), 앨범 `/api/v1/albums`·`/me/albums`(NTC-011, 파일 purpose ALBUM_MEDIA 영상 100MB), 채널 옵션 `/api/v1/settings/notification-channels`(SET-004), 위치 동의 `/api/v1/me/children/{id}/location-consent`(PAR-008: 행이 없으면 동의, 철회하면 위치 확인 QR 이 LOCATION_REQUIRED). 웹 ce1c707 — `/messages`·`/absence-requests`·`/notice-templates`·`/surveys`(+new,[id])·`/albums`·설정 "알림 채널" 탭, 알림장 작성 템플릿 불러오기·저장, 알림장 상세 "학부모 회신" 링크. 앱 82906ff — 학부모 더보기에서 메시지·결석 신청·설문·앨범·위치 동의 토글, 알림장 상세 "선생님께 회신", 교사앱 탭 5개(메시지·신청 추가), 푸시 type chat/survey/album/absence_request 이동. 미구현: 상담 예약(NTC-008 후반), 웹의 교직원 "새 대화" 시 보호자 선택(원생 기준으로 보호자마다 대화 생성)
- ③ 원생·학급·일정 완료(코드만, 빌드·실행 미검증): 백엔드 ec29dea (V16) — 건강정보 `/api/v1/students/{id}/health`(알레르기·지병·메모 AES-GCM 암호화, 교사는 담당 반만)·`/classes/{id}/health-alerts`, 상담일지 `/students/{id}/counsel-notes`·`/counsel-notes/{id}`·`/me/counsel-notes`(학부모 공개 표시분만), 태그 `/student-tags`·`/students/{id}/tags`(태그는 NoticeTarget 이 아니라 `/student-tags/{id}/students` 로 원생을 받아 STUDENT 대상으로 사용), 시간표 `/timetable`·`PUT /classes/{id}/timetable`(같은 반 겹침 검사)·`/me/timetable`, 대기자 `/waitlist`(+`/vacancies`, `PATCH /{id}/status`, `POST /{id}/contacted`; 자동 알림 없음 — 대기자는 앱 계정이 없고 알림톡 어댑터도 없어 원장이 직접 연락 후 "연락함" 기록), 교직원 출퇴근 `/staff-attendance/(check-in|check-out|today|monthly)`, 행사 템플릿 `/event-templates`(+`POST /{id}/generate` 날짜 목록 또는 기간+요일)·달력 `/events/calendar?month=`. 웹 abb5d55 — 원생 상세에 건강정보·태그·상담일지 카드, `/timetable`·`/waitlist`·`/staff-attendance`·`/event-calendar`·`/event-templates`, AdminShell 메뉴(활성 메뉴 판정을 경로 경계 기준으로 수정). 앱 5c0df7c — 교사앱 반 출결 화면 알레르기 주의 배너, 계정 메뉴 "출퇴근" 화면. 미구현: 학부모 앱의 상담일지·시간표 화면(API만 있음)
- ④ 결제·구독 완료(코드만, 빌드·실행 미검증): 백엔드 5f8cd97 (V17) — 청구 `/api/v1/invoices`(POST 발송: 원생별 금액 목록·기한·`notify`, GET `?month=YYYY-MM&status=ALL|UNPAID|OVERDUE|PAID|CANCELED&classroomId`, `/summary`, `POST /{id}/paid {method,paidOn,note}`·`/unpaid`·`/cancel`·`/remind`; 원장·실장 전용, 연체는 DB 상태가 아니라 `미납 + 기한 경과` 계산값), 학부모 `/api/v1/me/invoices`(발송된 건만, 취소 제외), 미납 자동 리마인드 `InvoiceReminderScheduler` 매일 09:30 KST(단계 DUE_SOON 1~3일 전·DUE_DAY·OVERDUE_1 1~6일 경과·OVERDUE_7 7~30일 경과, `invoice_reminder_log` PK 로 단계당 1회), 구독 `/api/v1/subscription`(+`/plans`·`PUT /plan`(원장만, 원생 수가 한도 초과면 OVER_LIMIT)·`PUT /card`(카드사·끝 4자리만)·`PUT /cancel`·`/payments`), 위젯 `/api/v1/dashboard/billing`, 알림 카테고리 `PAYMENT` 추가(SET-004, V17 에서 check 제약 교체). 플랜은 사업계획서대로 TRIAL(14일)·BASIC 49,000원(한도 49명)·PRO 99,000원(한도 149명)·ENTERPRISE(별도 협의), 구독 행은 처음 조회할 때 14일 체험으로 만든다. 웹 ae59458 — `/invoices`(청구서 발송 다이얼로그·수납 처리·되돌리기·취소·다시 알림), 설정 "구독·결제" 탭, 대시보드 구독·수납 위젯, 메뉴 "수납 관리". 앱 ffcbd73 — 학부모 더보기 "납부 내역"(`/invoices`), 푸시 type `invoice` 이동·실시간 갱신. 주의: PG 연동이 없어 플랜 변경은 즉시 반영될 뿐 실제 청구는 안 한다(결제 이력은 ⑤ 운영사 화면에서 기록 예정), 알림톡 어댑터가 없어 알림은 앱 푸시만 나간다, 원생 수 한도는 플랜 변경 시에만 검사하고 원생 등록 시에는 막지 않는다
- ⑤ SaaS 운영 백오피스 완료(코드만, 빌드·실행 미검증): 백엔드 9a94a00 (V18) — 운영사 계정은 `platform_admin(user_id)` 에 SQL 로 직접 넣어 지정(`insert into platform_admin (user_id) values ('…')`; 운영사 사용자도 일반 membership 이 하나는 있어야 로그인·기관 선택이 된다). `GET /api/v1/platform/me`(웹이 콘솔 메뉴 노출 판단), `/api/v1/platform/**`(기관 헤더 불필요, 운영사만): institutions(목록 `?status&q`·상세·`/approve`·`/suspend {reason}`·`PUT /subscription` 직접 변경), payments(`GET ?status&month`, `POST /institutions/{id}/payments` 결제 결과 기록 — 성공이면 구독 ACTIVE + 다음 결제일, 실패면 PAST_DUE + 원장·실장 푸시, `/{id}/retry`·`/refund`), vouchers(`GET`, `PUT·DELETE /institutions/{id}/voucher`, `voucher-settlements` 조회·`/generate?month`·`/{id}/settle`; 정부 지원분 = 구독 월 요금 × 지원 비율), tickets(`GET`, `/{id}`, `POST /{id}/messages` 운영사 답변 시 OPEN→IN_PROGRESS + 기관 푸시, `PATCH /{id} {status,assignToMe}`), notices(`GET·POST`, `/{id}/cancel`), metrics(`GET ?month` MRR·ARR·유료/체험 기관·신규·해지·Churn·CAC·6개월 추이, `PUT /metrics/{month}/marketing-cost`). 기관 쪽: `POST·GET /api/v1/support/tickets`, `GET /{id}`, `POST /{id}/messages`(원장·실장), `GET /api/v1/platform-notices/active`(로그인 사용자 배너용). 기관 서비스 상태(`institution.service_status` PENDING/ACTIVE/SUSPENDED)는 `AccessGuard.require` 가 직원용 API 에서 정지·승인 대기 기관을 막는다(학부모 앱은 막지 않음). 매일 00:10 KST `SubscriptionCancelScheduler` 가 '기간 종료 시 해지' 예약 구독을 해지 처리. 웹 2f4aafa — `/platform`(운영 콘솔: 지표·기관·구독 결제·바우처·문의·공지 탭, `platform/me` true 일 때만 메뉴), `/support`(문의하기, 원장·실장), 상단 공지·점검 배너. 앱 변경 없음. 주의: 가입 시 승인 대기로 만드는 연동은 아직 없어 신규 기관은 바로 ACTIVE, MRR 은 과거 요금 이력이 없어 현재 플랜 가격으로 근사, PG 가 없어 결제는 운영사가 결과를 직접 기록, 공지는 배너만(푸시 없음), `AccessGuard` 생성자에 `InstitutionServicePort` 가 추가돼 직접 생성하는 테스트는 고쳐야 한다
- ⑥ 셔틀 GPS 관제(ATT-009) 완료(코드만, 빌드·실행 미검증): 백엔드 21b11fe (V19) — 노선 `shuttle_route`(차량·번호·기사·기사 연락처)·정류장 `shuttle_stop`(순서·좌표)·탑승 `shuttle_rider`(원생→정류장)·운행 `shuttle_trip`(노선당 RUNNING 1건, 마지막 위치·next_seq 캐시)·좌표 `shuttle_position`(30일 보관)·도착 알림 기록 `shuttle_stop_alert`. API: `/api/v1/shuttle/routes`(GET 직원 `?all`, POST·PUT 원장·실장, `PUT /{id}/stops` 순서대로 전체 교체·id 있으면 유지, `GET·PUT /{id}/riders`), `POST /shuttle/routes/{id}/trips`(직원 누구나 출발 → 탑승 원생 보호자에게 SHUTTLE_DEPARTED 푸시), `POST /shuttle/trips/{id}/positions {points:[{lat,lng,recordedAt}],speed}`(출발한 기사만, 최대 500개, 오프라인 묶음 허용·늦게 온 옛 좌표는 최신 위치를 덮지 않음), `POST /{id}/end`(기사 또는 원장·실장), `GET /shuttle/live`·`/shuttle/trips/{id}`·`/path`·`GET /shuttle/trips?date`(원장·실장), 학부모 `GET /api/v1/me/shuttle?childId`. ETA 는 지도 API 없이 직선거리×1.3 ÷ (기기 속도와 25km/h 평균), 중간 정류장 30초 정차, 정류장 80m 안에 들어오면 지난 것으로 처리; ETA 3분 이하 또는 500m 이내면 그 정류장 보호자에게 SHUTTLE_ARRIVING 푸시(운행·정류장당 1회, 알림 카테고리는 ATTENDANCE). `ShuttleHousekeepingScheduler` — 10분마다 3시간 넘게 위치 없는 운행 자동 종료, 매일 04:10 KST 30일 지난 좌표 삭제. 웹 6c21208 — `/shuttle`(실시간 관제 지도·정류장별 ETA 10초 갱신, 노선·정류장 편집(지도 클릭으로 추가)·탑승 원생 배정, 운행 기록·경로), Leaflet+OpenStreetMap(`leaflet` 의존성 추가, `NEXT_PUBLIC_MAP_TILE_URL` 로 타일 교체 가능). 앱 acb9e7a — 교사 메뉴 '셔틀 운행'(`/shuttle-drive`: 노선 선택→출발, 위치를 모아 10초마다 전송, 안드로이드는 포그라운드 서비스 알림으로 화면 꺼져도 계속, iOS 는 앱이 화면에 있을 때만), 학부모 더보기 '셔틀 위치'(`/shuttle`: 정류장까지 ETA·남은 정류장 수·지도·기사 전화, 10초 갱신), 푸시 type `shuttle` 이동. `flutter_map`·`latlong2` 의존성과 안드로이드 FOREGROUND_SERVICE(_LOCATION)·WAKE_LOCK 권한 추가. 주의: 수집하는 위치는 차량(운행 중 기사 폰)뿐이고 아이 위치는 수집하지 않아 PAR-008 동의와 연결하지 않았다(기사 위치정보 수집 동의·위치정보사업 신고 여부는 법무 확인 필요), OSM 공용 타일은 상용 트래픽에 쓰면 안 돼 운영 전 타일 서버 교체 필요, ETA 는 도로를 모르는 추정치
- ⑦ 인증·온보딩(AUTH-006·007·010, ONB-002~004) 완료(코드만, 빌드·실행 미검증): 백엔드 c6770d5 (V20) — AUTH-007 TOTP 2단계 인증(`user_otp`: 비밀키 FieldCrypto 암호화, 복구 코드 8개는 HMAC 해시만, 같은 30초 구간 재사용 차단): `GET /api/v1/me/otp`, `POST /me/otp/setup`(otpauth URI)·`/enable {code}`(복구 코드 1회 반환)·`/disable {password, code}`·`/recovery-codes {code}`; 로그인은 OTP 켠 계정이면 `POST /auth/login` 이 **HTTP 202** `{otpRequired, challengeId, expiresAt}` 를 주고 `POST /auth/login/otp {challengeId, code}`(5분·5회, 복구 코드 허용)로 토큰 발급 — 기존 클라이언트는 202 를 처리해야 한다. AUTH-006 소셜(`user_social`, `social_signup`): 앱이 SDK 로 받은 access token 을 `POST /auth/social/{KAKAO|NAVER} {accessToken}` 로 보내면 서버가 카카오 `access_token_info`(app_id 확인, `KAKAO_APP_ID`)·`/v2/user/me`, 네이버 `/v1/nid/me` 로 확인 → 연결된 계정이면 토큰(또는 OTP 202), 아니면 `{signupRequired, signupToken, name, email, phone}` → 학부모는 `POST /auth/social/signup {signupToken, name, phone}`(30분, 보호자 번호로 자녀 자동 연결, 비밀번호는 임의값). `POST /auth/social/providers`, `GET /me/social`, `PUT·DELETE /me/social/{provider}`(소셜로 가입한 연결은 해제 불가). 서버 설정 `KAKAO_LOGIN_ENABLED`·`KAKAO_APP_ID`·`NAVER_LOGIN_ENABLED`(기본 꺼짐). AUTH-010 `institution.session_idle_minutes`(기본 30, 0=끔, 5~480): `GET·PUT /api/v1/institution/session-policy`(변경은 원장·실장). ONB-003 `institution.is_sample`: `GET·POST /api/v1/sample-institution`(계정당 1곳, '[체험] 똑똑 샘플학원' + 반 3·목적지 4·원생 18(보호자 없음)·지난 2주 출결·오늘 예정), `DELETE /{id}`(소속 삭제 + 정지), 운영 지표에서 샘플 기관 제외. 웹 adf2ff3 — 로그인 OTP 단계(`/api/auth/login` 이 otpRequired 전달, `/api/auth/login/otp` 신규, 공통 `lib/server/finishLogin.ts`), 설정 '보안' 탭(OTP 켜기 QR·복구 코드·끄기, 자동 로그아웃 시간), `IdleLogout`(마우스·키보드만 활동으로 셈, 탭 간 localStorage 공유, 1분 전 경고), 반 관리 '템플릿으로 만들기'(초등 방과후·보습·예체능·공부방·어린이집 5종, 수정 후 일괄 생성 — ONB-002 는 백엔드 없이 웹 데이터), 대시보드 체험 배너·계정 메뉴 '샘플 데이터로 체험하기', 기관 전환 시 새 소속이면 토큰 갱신. 앱 693d819 — 로그인 화면 카카오·네이버 버튼(서버와 앱 키 모두 있을 때만), 학부모 소셜 가입 화면, 간편 로그인 연결 화면(교사 메뉴·학부모 더보기), OTP 입력 다이얼로그, 교사앱 첫 실행 3단계 출결 튜토리얼(shared_preferences, 메뉴에서 다시 보기). `kakao_flutter_sdk_user`·`flutter_naver_login` 의존성, Android `MainActivity` → `FlutterFragmentActivity`, 카카오 AuthCodeCustomTabsActivity·네이버 meta-data(키는 `~/.gradle/gradle.properties`), iOS Info.plist URL scheme·쿼리 스킴(키는 gitignore 된 `ios/Flutter/Social.xcconfig`). 주의: iOS 는 UIScene 구조라 네이버 로그인 복귀 URL 처리(`SceneDelegate` 에서 `NidOAuth.shared.handleURL`)를 실기기로 확인하며 추가해야 하고, 두 SDK 의 AGP 9 호환은 빌드로 확인 필요, IA 의 'SMS OTP' 는 SMS 어댑터가 없어 인증 앱 방식만, 자동 로그아웃은 브라우저 쪽 판단(서버 강제 아님), AuthController 생성자에 의존성이 늘어 직접 만드는 테스트는 고쳐야 한다
- Phase 2 잔여분(외부 결정 불필요분) 완료(코드만, 빌드·실행 미검증): NTC-008 상담 예약 — 백엔드 a019ff8 (V21 `consult_slot`: 교사·원장이 연 슬롯을 학부모가 예약, 교사·시각 중복 방지 부분 유니크, 예약은 `status='OPEN'` 조건부 update 로 동시성 처리): 교직원 `POST /api/v1/consult-slots {date, startTime, minutes, count(1~12), breakMinutes, mode IN_PERSON|PHONE|VIDEO, location, teacherId(원장·실장만 타인 몫)}`, `GET ?from&to&teacherId`(교사는 본인 것만), `POST /{id}/cancel {reason}`(예약자에게 CHAT_CONSULT_CANCELED 푸시)·`/done`, `DELETE /{id}`(예약 없을 때); 학부모 `GET /api/v1/me/consult-slots?childId`(아이의 현재 반 담당 교사 + 그 기관 원장·실장이 연, 30분 이후 빈 슬롯), `GET /me/consult-bookings`, `POST /me/consult-slots/{id}/book {studentId, topic}`(교사에게 CHAT_CONSULT_BOOKED 푸시), `POST /me/consult-bookings/{id}/cancel`(시작 전까지, 슬롯 다시 열림). 웹 b84fa01 `/consults`(2주 단위, 연속 슬롯 열기·취소·완료). 앱 5948738 — 학부모 더보기 '상담 예약'(예약하기·내 예약)·'상담 기록'(`/me/counsel-notes`)·'주간 시간표'(`/me/timetable`), 교사 메뉴 '상담 예약', 푸시 type `consult`. STAT-001/008 PDF 리포트 — 웹 fd8d6de `/print/stats?from&to&sections`(관리자 레이아웃 밖 A4 화면, 그려지면 인쇄 창 → 'PDF로 저장'; 서버 PDF 라이브러리·한글 폰트 없이 브라우저가 렌더링), 통계 화면에 'PDF 리포트' 버튼. SAAS-001 가입 승인제 — 백엔드 e5b2675: `ttok.platform.require-approval`(`REQUIRE_INSTITUTION_APPROVAL`, 기본 false)이면 원장 가입 직후 기관을 PENDING 으로, `GET /api/v1/institution/service-status`(정지·대기여도 조회), `AccessGuard.requireMemberAnyStatus` 를 추가해 정지·대기 기관도 운영사 문의(SAAS-004)는 쓸 수 있게 고침(이전엔 막혀 있었음). 웹 15e72d5 상단 승인 대기·정지 안내 배너. 남은 Phase 2: SEC-005·006(운영 환경 결정), AUTH-006 키 발급·iOS 복귀 처리, ATT-004 교육청 양식
- Phase 3 계획(2026-10-10): https://claude.ai/code/artifact/57aabf5e-4ba8-4bbc-9470-9bb0f1c415a4 — IA Phase3 22건. 선행 0(Phase 2 빌드 검증) → P3-① 안전·운영(ATT-010 미등원 초과 경고, STAT-007, STF-005, CLS-005, EVT-009) → P3-② 수납·내보내기(PAY-006, PAY-007, SET-006) → P3-③ 플랫폼(SAAS-006, SET-007) → P3-④ 광고·커머스 8건(광고 규제 검토 후) → P3-⑤ PG·발행대행·번역 API 연동 4건(계약 후)
- 이미 구현돼 Phase 2 에서 제외: AUTH-009 다기관(7-8), ATT-008 QR(학생앱), STU-008

## Phase 3 진행 (2026-10-10~)

- P3-① 안전·운영 고도화 (백엔드 3c83498, 웹 89805ed, 앱 e35ba7c): V22.
  - ATT-010 미등원 초과 경고: `MissingAlertScheduler` 5분(ShedLock `attendance.missingAlert`). 수업 시작 + 기관 기준 분(기본 30, 0=끔, 10~180) 지난 SCHEDULED 출결 → 원장·실장·담당 교사(+옵션 보호자) 푸시 1회(`attendance_missing_alert`). 결석 신청 제외, 지각 신청은 예상 시각 기준, 다기관 아이는 같은 날 다른 기관 하원 기록을 문구에 포함. 최근 2시간 창만 봐서 재시작 직후 몰아 보내지 않음. API `GET/PUT /institution/missing-alert`, `GET /attendance-missing-alerts?date`. 웹 설정 탭·대시보드 위젯, 앱 푸시 type `attendance_missing`
  - STAT-007·STF-005: `GET /stats/notice-ranking`, `/stats/staff-work` (원장·실장, 최대 1년). (알림장×원생) 단위 열람률, 기관 평균 −10%p·대상 5명 이상이면 취약. 교사별 발송·답장·평균 응답(학부모 메시지 → 첫 교직원 답장)·출결 처리·등원 처리 시점·상담 완료. 웹 통계 > 소통·업무 탭
  - CLS-005: `classroom_textbook`·`classroom_progress`, `/classes/{id}/textbooks|progress`, `/textbooks/{id}`, `/progress/{id}`, `POST /curriculum/quote`. 웹 반 카드 > 교재·진도, 알림장 작성 '진도 불러오기'
  - EVT-009: `album.event_id`, `event_review`. 교직원 `/events/{id}/albums|reviews`, `POST /event-reviews/{id}/hidden`; 학부모 `/me/events/{id}/albums|reviews`, `PUT/DELETE /me/events/{id}/review`(행사 시작 후, 대상 자녀 보호자, 1인 1건). 앱 행사 시트 하단
- P3-② 수납 고도화·데이터 내보내기 (백엔드 7c584cd, 웹 2d8ff4f): V23.
  - PAY-006: `discount_rule`(SIBLING n째부터 / LONG_TERM n개월 / MULTI_CLASS n개 반, PERCENT·AMOUNT, 최대 20개), `/discount-rules` CRUD, `POST /invoices/discount-preview`, `POST /invoices/with-discounts`(기존 발행을 그대로 부르고 invoice.base_amount·discount_amount·discount_note·period_start/end 기록). 형제 = 같은 기관에서 보호자 phone_hash 공유, 먼저 등록한 순. 퍼센트는 10원 버림, 합계는 청구액 이하. 웹 청구 다이얼로그는 이 엔드포인트로 바뀜(기존 `POST /invoices` 그대로 있음)
  - PAY-007: `POST /invoices/{id}/refund-calc`(LAW 학원법 1/3·1/2 기준을 달력 일수로, 1개월 넘는 기간은 월 단위로 나눠 이후 달 전액 / DAILY 일할), `POST /invoices/{id}/refund`(청구서당 1건, 금액 수정 가능, withdrawReason 주면 기존 ChangeStudentStatusUseCase 로 퇴원 처리), `GET /refunds?month`. 송금은 직접
  - SET-006: `GET /institution/export.zip?from&to`(원장, 출결·알림장 최대 1년) 시트 9개 xlsx + README, 생년월일·보호자 전화 복호화 포함, 감사 로그 `DATA_EXPORT`. 웹 설정 > 데이터 내보내기
- P3-③ 플랫폼 (백엔드 e82d7d4, 웹 a3e526b, 앱 f05569e): V24.
  - SAAS-006: `UsageMeteringFilter`(보안 필터 앞, `/api/v1/**` 중 `X-Institution-Id` 있는 요청을 기관×시간으로 요청·5xx·401/403·429·사용자 수, 로그인 성공·실패는 플랫폼 행 00000000-…) → 1분마다 `api_usage_hourly` upsert. `UsageAnomalyScheduler` 매시 5분(ShedLock `usage.detect`): 직전 7일 같은 시각 평균 대비 5배·300건 이상 급증, 오류율 10%(50건 이상), 401·403 100건, 429 20건, 로그인 실패 100회, 매일 9시 판정분에 3일 무사용 → `usage_alert`(기관·종류·시각 유일). 90일 지난 사용량 04:20 삭제. API `/platform/usage?days`, `/platform/usage/hourly`, `/platform/usage-alerts`(+`/{id}/ack`). 웹 운영 콘솔 > 사용량·이상 탐지. 학부모 `/me` 요청은 기관 헤더가 없어 기관별 집계에서 빠짐. 필터가 `UsageMeteringUseCase` 를 받으므로 `@WebMvcTest` 슬라이스 테스트는 MockBean 추가 필요
  - SET-007(부분): `app_user.locale`(ko·en), `GET/PUT /me/locale`. 웹 `lib/i18n.tsx`(한국어 문구 키 사전, localStorage + 계정 동기화, 날짜 선택기 로케일) — 사이드·계정 메뉴, 화면 제목, 설정 탭, 로그인 화면만 영어. 앱 `core/i18n/i18n.dart` — 하단 탭·더보기만. 나머지 화면 문구와 서버 오류 메시지(한국어 하드코딩)는 화면별로 사전에 더해 나가야 함
- SET-007 이어서 (2026-10-12, 백엔드 1b76a36, 웹 684c601, 앱 ef1a512): 
  - 백엔드: `RequestLanguage`(Accept-Language 첫 언어가 en 이면 영어, 헤더 없으면 한국어 — JVM 로캘 무관), `ErrorMessagesEn`(한국어 문장 그대로 → 숫자·이름이 낀 문장 정규식 → 코드별 → 상태별 순으로 영어). `ErrorHandler`·`AuthRateLimitFilter` 가 요청 언어로 응답, 검증 오류 details 도 영어. code 는 언어와 무관. 새 오류 문장을 추가하면 `ErrorMessagesEn.EXACT`/`PATTERNS` 에 영어도 넣을 것(없으면 코드·상태별 일반 문장)
  - 웹: `t('원문', {0: 값})` 자리표시, 컴포넌트 밖 헬퍼용 `translate()`, 화면 문구 사전 `src/lib/i18n-en.ts`(약 1,270개, dayjs 형식 문자열 포함). 클라이언트 화면 전부를 코드모드로 `t()` 감쌈(컴포넌트마다 `const t = useT()`, 이미 `t` 변수가 있는 파일은 `tr`). 언어는 `ttok_locale` 쿠키에도 저장 → BFF(proxy·proxy-public·로그인·OTP·초대 수락)가 `Accept-Language` 전달. `errorMessage()` 는 BFF 한국어 문장도 사전 변환. 남은 것: 서버 컴포넌트 metadata(브라우저 탭 제목), 시작하기(onboarding, 사용자 미커밋 변경이 있어 제외), 사용자가 고르는 데이터 값(관계 '어머니' 등 — 표시만 번역, 저장 값은 한국어)
  - 앱: 어디서나 쓰는 `tr('원문', [값])`·`dateLocale()`·`currentLocale`(core/i18n/i18n.dart), 사전 `core/i18n/i18n_en.dart`(약 430개). 화면 문구를 코드모드로 `tr()` 감싸고 그 자리의 `const` 를 뗌(빌드 때 남은 const 오류가 있으면 해당 `const` 제거). 언어를 바꾸면 `MaterialApp.router` builder 의 `KeyedSubtree(ValueKey(locale))` 로 전체 다시 그림. 날짜 `DateFormat(tr('M월 d일 (E)'), dateLocale())`, bootstrap 에 `initializeDateFormatting('en_US')` 추가. Dio(ApiClient 의 dio·_raw, StudentApi)가 `Accept-Language` 전송. 제외: 교사 attachment_field·content_repository·event_detail_screen(사용자 미커밋 변경)
  - 공통 남은 것: 서버가 만들어 저장·발송하는 한국어(푸시 본문, 엑셀 시트·헤더, 사용량 경고 문장, 엑셀 업로드 행 오류, 안드로이드 알림 채널 이름)
- P3-⑥ 역할별 대시보드 DASH-008~023 (2026-10-12 기능명세서 v1.1 추가분; 백엔드 4212111, 웹 6e5dc07, 앱 6e69e53): V25.
  - 백엔드(`RoleDashboard*`): 원장·실장 `GET /dashboard/owner/{kpi,classes,inbox,billing,churn-risks,staff}`, `GET /me/dashboard/institutions`(기관 헤더 없음, 원장·실장 소속 기관 비교). 반 현황 배지 NO_TEACHER·FULL·MISSING·LOW_READ(반 평균 −10%p, 반 2개 이상일 때). 이탈 위험은 `OwnerDashboardService.score` 규칙 점수(결석률 +20%p·결석 3회↑ +40, 30일 알림장 3건↑ 중 70%↑ 미열람 +25, 60일 상담일지 퇴원·그만·옮기·이사·중단·휴원 언급 +30, 연체 +20, 90일 휴원 복귀 +15; 40점↑만, 기관별 조정은 아직 없음). 지난달 재원생은 '현재 − 이번 달 신규 + 이번 달 퇴원' 근사.
  - 교사 `GET /dashboard/teacher?teacherId=` — 본인이 담당한 반 기준(원장·실장도 자기 담당 반만), 다른 교직원 것은 원장·실장만(웹 '교사 화면 보기', 읽기 전용). 미등원 = 시작+기관 지각 기준 경과 SCHEDULED(결석 신청 제외), 하원 미처리 = 종료 30분 경과 IN, 보호자 번호(대표 우선) 복호화 포함. 주의 원생은 오늘 출결 대상 중 건강(알레르기·지병)·생일·당일 사전 신청·최근 7일 상담일지 — text/sub 는 입력 데이터 그대로라 라벨은 화면이 kind 로 붙임.
  - 교직원 공지 `staff_memo`(+target, ack): `POST/GET /staff-memos`(원장·실장, 대상 비우면 전체 교직원), `GET /staff-memos/{id}/acks`, `DELETE /staff-memos/{id}`(취소), `GET /staff-memos/mine`, `POST /staff-memos/{id}/ack`(본문 없음 = 읽음, {done}=완료 표시/해제). 받는 교직원에게 교사 푸시 type `staff_memo`(템플릿 STAFF_MEMO).
  - 위젯 배치 `user_dashboard_layout`: `GET/PUT/DELETE /me/dashboard-layout/{OWNER|TEACHER}` {widgets:[{key,visible}]}. 저장 없으면 빈 목록 → 화면 기본 배치, 새 위젯은 끝에 보이게 붙음(`mergeLayout`).
  - 웹: `/dashboard` 가 역할로 분기(원장·실장 = 원장 대시보드: 경영 KPI·처리 대기함·반별 현황·수납·이탈 위험·교직원·공지·기관 비교 + 기존 DASH-001~007, 교사 = 교사 대시보드 6개 위젯). `?teacherId=` 교사 화면 보기, '위젯 설정' 다이얼로그. 파일 `src/lib/dashboard.ts`, `src/components/dashboard/*`. page 에 Suspense 추가.
  - 앱: 교사 홈 맨 위 '오늘 할 일' 요약 카드 → `/today`(`features/teacher/teacher_dashboard.dart`), 출퇴근·공지 완료·보호자 전화(tel:). 진도 기록은 앱에 화면이 없어 안내만.
  - 남은 것: 이탈 위험 기준의 기관별 설정, 앱 위젯 배치(웹만), 다기관 비교의 기관 전환은 웹만
- P3-⑦ 익명 커뮤니티 COM-001~009 (2026-10-12 기능명세서 v1.2 추가분; 백엔드 49ff88b, 웹 de8f184, 앱 e847118): V26. 학생 커뮤니티 COM-010 은 보류(미성년자 보호 설계 후).
  - 게시판은 표 없이 키로: `L-OWNER`(원장)·`L-STAFF`(실장·교사)·`L-PARENT`(학부모) 전국 라운지, `I-{기관ID}-STAFF`·`I-{기관ID}-PARENT` 우리 기관. 자격은 매 요청 재계산 — 교직원 membership(OWNER/ADMIN/TEACHER), 학부모 = guardian.user_id 연결(LINKED)·퇴원 아닌 자녀. 샘플 기관·service_status≠ACTIVE 제외. 기관 헤더 없이 사용자 단위(앱은 `noInstitution`).
  - 익명: author_id 는 저장만, 응답엔 `mine`·익명 번호만. 라운지 글은 '역할 · 시도'(institution.address 첫 단어 → 서울·경기·충북 …), 기관 게시판은 표시 없음('익명'). 댓글은 글마다 고정 번호(`community_anon`, 0 = 글쓴이), 답글 한 단계, 삭제 댓글은 '삭제된 댓글'로 남김.
  - API `/community/boards|me|rules`, `/community/boards/{key}/posts?sort=LATEST|POPULAR&category&q&page`(20건, 인기 = 최근 7일 공감×2+댓글), `/community/posts/{id}`(GET·PUT·DELETE), `/posts/{id}/comments`, `/comments/{id}`, `/{posts|comments}/{id}/like|report`, `/community/me/posts|comments`(내 글 첫 페이지를 보면 새 댓글 수 초기화).
  - 안전: 첫 글·댓글 전에 이용 규칙 동의(`RULES_REQUIRED`), 전화번호·이메일 자동 가림, 금칙어 차단(`CommunityService.BANNED` — 운영하며 보강), 글 10분 5건·댓글 1분 10건 제한, 신고(사유 5종, 1인 1회, 본인 글 불가) 서로 다른 신고 라운지 5·기관 3건이면 REPORTED 숨김(작성자에게만 보임).
  - 운영사(COM-008): `/platform/community/reports?status=OPEN|RESOLVED`, `/platform/community/{POST|COMMENT}/{id}/resolve`{action HIDE·RESTORE·DELETE·DISMISS}·`sanction`{days 1·7·30|null=영구, reason}·`lift-sanction`. 콘솔에도 작성자 신원은 안 보이고 글·댓글로만 제재. 수사기관 요청 등은 DB 에서 author_id 조회.
  - 웹 `/community`(소통 메뉴, 원장·실장·교사), 운영 콘솔 '커뮤니티 신고' 탭. 앱 `/community`·`/community/me`·`/community/posts/:id` — 교사앱 계정 메뉴, 학부모앱 더보기 '학부모 커뮤니티'. 학생앱 없음.
  - 남은 것: 댓글 알림 푸시(현재 푸시 이벤트가 기관 단위라 '내 활동' 새 댓글 수만), 금칙어 목록 운영 관리 화면, 이미지 첨부(익명성·초상권 때문에 보류), 신고 처리 결과 통지
- 앱 UI/UX 디자인 적용 (2026-10-13, 앱 f2096fb): 기준 = 프로젝트 문서 `claude/design/app_uiux_design.md`·디자인 캔버스(42화면). 이번엔 P1 핵심 화면 + 공통 테마.
  - 공통: `app/theme.dart` 토큰 교체(Ink #17233A·Ground #F4F2EC·Accent #FFCE3D, 등원 #E3EAFD/#1F47B8·하원 #FCE9DA/#9A400A·버튼 #C2560F·예정·경고 bg/fg 쌍, 모서리 카드 16–22·버튼 16), 서체 IBM Plex Sans KR 번들(`assets/fonts`, OFL → **flutter pub get 다시**). Accent 노랑은 흰 바탕 글자 대비가 안 나와 채움 전용, 기존에 글자·아이콘으로 쓰던 `AppColors.accent` 는 `highlight`(#D9480F)로 바꿈. 기존 의미 이름(primary·success·info 등)은 새 토큰에 연결해 나머지 화면도 색이 따라감. `StatusBadge`·`Pill.tone` bg/fg 쌍, `core/widgets/logo_mark.dart`
  - 로그인(P_Login·T_Login): 학부모 Ground 라이트, 교사 Ink 다크 + Accent 로그인 버튼, 간편 로그인 2열. 비밀번호 찾기(AUTH-004·005) 화면은 앱에 아직 없어 링크 생략
  - 학부모(Main): 헤더 로고·알림 버튼(알림 센터 PAR-002 화면이 없어 알림장 탭으로), 자녀 버튼(이니셜 아바타, 선택 = Ink), 오늘 아이별 '지금' 다크 카드(타임라인 최신 기록으로 — 다음 일정 줄은 데이터 없어 생략), 안 읽은 알림장·응답 대기 행사 카드, 레일형 타임라인. 하단 탭 5개 홈·알림장·일정·행사·더보기(`ScheduleTab(section:)` 로 일정/행사 분리, 푸시 event → 탭 3)
  - 교사(T_Classes·T_Attendance·T_Dismiss): 첫 탭 이름 '내 반', 홈 제목 '내 담당 반 N개', 반 카드에 오늘 등원·미등원·결석(`/dashboard/teacher` classes 재사용), 맨 아래 알림장 쓰기. 출결 화면 4칸 요약, 흰 카드 목록, [출석] 파랑 / [하원] 주황 테두리, 하단 '미등원 전원 출석'(확인 대화상자)·'일괄 하원'(목적지 하나) — 기존 원터치 경로를 한 명씩 반복. 하원 목적지 시트는 라디오 선택 → [하원 처리 → 목적지] 두 단계로 바뀜
  - 교사 하단 탭은 디자인(내 반·출결·알림장·원생 4개)이 아니라 기존 5개(내 반·알림장·행사·메시지·신청) 유지 — 원생 상세(STU-009) 화면이 앱에 없음
  - 남은 것: P2·P3 화면(채팅·앨범·설문·청구·커뮤니티 등)은 테마 색·모서리만 따라감 — 화면별 레이아웃은 미적용. 빌드 검증 안 함(위젯 상수·const 오류 가능)
- 관리자 웹 UI/UX 디자인 적용 (2026-10-14~, 브랜치 claude/great-maxwell-6rrhge): 기준 = 디자인 캔버스 「똑똑에듀 어드민 UI」(35화면 + 사이드바 2종). 디자인만 바꾸고 API·쿼리 키·권한·실시간·라우트는 그대로. 계획·매핑·디자인 차이 표 = 웹 `docs/design-apply-plan.md`
  - ① 토큰·테마 (웹 26ff092): `src/theme/tokens.ts`·`src/theme/index.ts`(기존 `src/app/theme.ts` 삭제), IBM Plex Sans KR·Mono 를 next/font/google 로(빌드 PC 인터넷 필요, Pretendard CDN 제거). 버튼 44/32, 카드 12px, 세그먼트 탭, 상태 배지 톤이 테마에서 전 화면에 적용됨
  - ② 공통 컴포넌트 (웹 81bd41e): `src/components/ui/*`(StatusBadge·MenuIdChip·SectionCard·KpiCard·SegmentTabs·ProgressBar·EmptyState), 상태→배지 톤 매핑 `src/theme/status.ts`, PageHeader 흰 바+브레드크럼, 메뉴 구성 `src/components/shell/nav.tsx`. 메뉴ID 칩은 `NEXT_PUBLIC_SHOW_MENU_IDS=false` 로 숨김
  - ③ 앱 셸 (웹 33ac150): `src/components/shell/Sidebar.tsx`(기관 #1B2559 / 운영사 #10163A), 기관 전환은 사이드바 '현재 기관' 버튼, 계정 메뉴는 하단 사용자 카드로 이동(로직 동일). 운영 콘솔 탭은 `/platform?tab=` 과 동기화돼 운영사 사이드바 메뉴가 됨. 로그인·초대 수락 = `AuthLayout`, 온보딩은 셸 없이(셸이 `/onboarding` 경로로 판단). md 미만은 상단 바 + Drawer
  - ④ Phase 1 화면 (웹 cd895b6·3a6c466): 대시보드(인사 줄·KPI 카드·LIVE 타임라인·세그먼트 탭, 역할별 구조·위젯 설정 유지), 원생 목록(한 카드: 상태 세그먼트·검색·아바타·배지), 데일리 리포트, 알림장·행사 목록, 설정 탭을 화면 머리로. 전 화면 공통: 카드 패딩 20×24, 표 첫·끝 칸 24px(테마), 색 있는 칩 = 배지 톤, 글자 상태색은 *.dark(대비 4.5:1)

## 남은 일
- 사용자 PC 에서: 관리자 웹 `npm install`·`npm run build`, 앱 `flutter pub get`(pubspec.lock 커밋)·세 flavor 실행 확인, 백엔드 빌드·V8 마이그레이션 적용 확인
- 앱: Firebase 프로젝트 연결·실기기 푸시 확인, 교사앱 알림장·행사 실기기 확인(카메라·앨범 권한, S3 업로드는 버킷 CORS·localstack 필요), 오프라인 큐 실기기 확인(비행기 모드로 등·하원 → 복구 후 자동 전송), PDF 첨부 열기 실기기 확인, 네이티브 flavor(학생앱 별도 스토어 앱)
- 앱 알림장·행사의 PDF 첨부 업로드·행사 명단 엑셀은 완료 (실기기 확인 필요: 파일 선택, 공유 시트)
- 교사용 푸시·실시간은 코드 작성만 (빌드·실기기 미검증): 백엔드 빌드 후 개인 큐 구독(관리자 웹 교사 화면은 구독 코드 추가됨, 앱은 TeacherShell; 교사 로그인 → `/user/queue/events`)과 FCM 교사 기기 수신 확인 필요. 학부모용 `/user/queue/events`(d27130a·ace65a2)도 코드만 — 학부모 로그인 후 소켓 수신 확인 필요
- 관리자 웹: 실제 백엔드와 연동 점검(응답 필드명·권한). 2026-10-08 정적 대조로 웹이 부르는 API 66개(메서드+경로)가 백엔드 매핑 109개에 모두 있음을 확인, 대시보드 today 응답 필드 일치 확인 — 나머지 응답 필드·권한은 실제 실행 때 확인. 통계(STAT)는 Phase 2 슬라이스 ①로 추가
- 백엔드
  - ATT-004 교육청 출석부 실제 양식 반영 (Open Issue 5, 원본 양식 확보 대기)
  - 빌드·테스트 검증, GitHub 푸시 (보류)
  - 미뤄둔 것: 학부모 SMS 인증(Open Issue 1), 알림톡 실제 어댑터(Open Issue 4), 약관 실제 본문(법무), Rate limit·KPI 카운터의 Redis 전환, RLS, 학부모 STOMP 는 완료

## 참고 문서
- 개발 스펙(Claude Docs): https://claude.ai/code/artifact/7c50ae42-0685-42cf-96e1-86323dea730d

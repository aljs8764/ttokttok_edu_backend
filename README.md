# 똑똑(Ttok-Ttok) Backend — S1 Core Loop

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

업무 API는 `Authorization: Bearer {access}` + `X-Institution-Id` 헤더를 쓴다. 오류 형식은 `{code, message, details}`.

## S1에서 의도적으로 미룬 것 (TODO)

- 학부모 가입 SMS 본인인증 (Open Issue #1) — 현재는 번호+비밀번호
- Refresh 토큰 회전·폐기 목록(Redis), 로그인 5회 실패 잠금
- PostgreSQL RLS·Hibernate Filter 기반 테넌트 이중 방어 — 현재는 모든 포트 조회에 `institutionId` 필수 + `AccessGuard`
- STOMP Redis 브로커 릴레이(서버 다중화), QueryDSL(조회 전용 Query Port)
- 알림톡 미가입 보호자 안내, 감사 로그(audit_log) — S3

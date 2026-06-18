# 예약/결제 플랫폼 개발 계획

> 프로젝트: 특정 시간(00시)에 오픈되는 **초특가 숙소 상품(10개 한정)** 에 대한 **선착순 예약 시스템** 구축
> 환경: 애플리케이션 서버 **2대 이상의 분산 환경**

---

## 1. 요구사항 분석 (전체 정리)

### 1.1 프로젝트 핵심 목표
| 목표 | 설명 |
|------|------|
| 동등한 기회 제공 | 모든 사용자가 동등한 확률/순서로 상품 구매 가능 (공정성) |
| 엄격한 재고 정합성 | 분산 환경 + 트래픽 집중에서 **미달/초과판매 0** |
| 안정적 동작 | 결제 실패·시스템 장애 상황에서도 데이터 일관성 보장 |

### 1.2 필수 구현 API
| API | 설명 | 책임 |
|-----|------|------|
| `GET Checkout` | 주문서 진입 | 상품 정보(명칭/가격/입·퇴실 시간 등) + 사용자 가용 포인트 조회 |
| `POST Booking` | 결제·예약 완료 | 주문서 정보 입력 → 결제 진행 → 최종 주문 생성 |

> 요청/응답 규격은 자유 설계.

### 1.3 핵심 요구사항 (5개, 전부 구현 + DECISIONS.md 근거)
1. **재고 정합성 및 공정성**
   - 00시 트래픽 집중에서 미달/초과판매 방지, 정합성 완벽 보장
   - 모든 사용자가 동등한 확률로 구매 가능한 구조
2. **고가용성**
   - TPS 급증 대응, 시스템 붕괴 방지 구조 → DECISIONS.md 기술
   - 평시 **50 TPS**, 00시 1~5분 순간 **500~1000 TPS** 예상
3. **멱등성 처리**
   - 짧은 간격 연속 결제 요청에도 중복 처리 방지
4. **결제 확장성**
   - 결제 수단: **신용카드, Y페이, Y포인트**
   - 복합 결제: `(신용카드 + 포인트)` 또는 `(Y페이 + 포인트)`
   - **신용카드 + Y페이 혼용 불가**
   - 새 결제 수단 추가 시 Booking API 비즈니스 로직 수정 최소화 구조 → DECISIONS.md 상세
5. **장애 대응 및 예외 처리**
   - **Redis 장애**: Fallback 전략 + 근거
   - **결제 실패**: 한도 초과 등 실패 케이스 대응 로직
   - DECISIONS.md 상세

### 1.4 참고/제약 사항
- 실제 PG 연동은 생략하되 **인터페이스로 구조적 흐름** 유지
- 회원 인증/로그인 보안은 평가 범위 외 (생략 가능)
- **인프라 증설(Scale-up/out)이 제한적인 상황** 가정 → 무한 확장 전제 금지

### 1.5 기술 스택 제약
| 항목 | 제약 |
|------|------|
| 언어 | Java 8 이상 또는 Kotlin (실무는 Java) |
| 프레임워크 | Spring Boot 2.7 이상 |
| RDB | MySQL 또는 MariaDB |
| Cache | Redis |
| Infra | 앱 서버 2대 이상 분산 |
| Other | 자유 선택, **도입 근거 DECISIONS.md 필수** |

### 1.6 제출물
1. **동작 가능한 소스 코드** — 코드 수정 없이 실행 가능 (추가 인프라 필요 시 README 명시)
2. **README.md** — 아키텍처 설명 + 실행 방법, 시퀀스 다이어그램/플로우차트, ERD 또는 DDL (주문/결제 도메인 중심)
3. **DECISIONS.md** — 주요 기술 쟁점 선택 근거, 라이브러리 도입 사유 및 문제 해결 전략
4. 산출물 전부 Git Public Repo + `docs/` 디렉토리 포함
5. **주의**: 저장소명/코드/문서에 특정 회사명·서비스·조직 식별 내용 미포함

---

## 2. 기술 스택 결정 (확정안)

| 항목 | 선택 | 근거 (→ DECISIONS.md) |
|------|------|------|
| 언어 | **Java 21 (LTS)** | "Java 8 이상" 충족. LTS·virtual thread로 동시성/IO 효율. SB 3.5.x·Lombok 정식 지원 |
| 프레임워크 | **Spring Boot 3.5.x** | "SB 2.7 이상" 충족. Java 25 런타임 지원 버전 사용 |
| 영속성 | **Spring Data JPA (Hibernate) + MySQL 8.4 (LTS)** | 프로젝트 명시 RDB. `SELECT ... FOR UPDATE`·낙관락(@Version)·유니크 제약으로 정합성 backstop |
| Cache/Atomic | **Redis 7** | 재고 카운터·멱등성 키·분산락. 단일 스레드 특성으로 전역 직렬화 |
| 보일러플레이트 | **Lombok** | 엔티티/DTO 보일러플레이트 축소 (build는 Kotlin DSL이나 소스는 Java) |
| 빌드 | **Gradle (Kotlin DSL, `build.gradle.kts`)** | 타입세이프 빌드 스크립트. 소스는 Java + Lombok |
| 분산락 | Redis Lua 직접 (옵션: Redisson) | 추가 의존성 없이 원자연산 우선. Redisson은 락 추상화 필요 시 검토 |
| 컨테이너 | **Docker Compose** | 앱 2대 + Nginx LB + MySQL + Redis 단일 명령 기동 (재현성) |
| 부하 테스트 | **k6** 또는 JMeter | 500~1000 TPS 버스트 재현·검증 |
| 문서 | OpenAPI(springdoc) | API 명세 자동화 |

> DDL: JPA `ddl-auto` 대신 **`schema.sql`/Flyway**로 명시 관리 권장 (재현성·운영 정합성). 테스트: JUnit5 + Testcontainers(MySQL/Redis) + Awaitility(동시성).
>
> 버전 조합: Spring Boot 3.5.x + Java 21(LTS) + Lombok — 모두 정식 지원 조합, 호환 리스크 낮음.

---

## 3. 시스템 아키텍처

```
                       ┌─────────────┐
        Clients  ───►  │  Nginx (LB) │  (라운드로빈)
                       └──────┬──────┘
                  ┌───────────┴───────────┐
            ┌─────▼─────┐           ┌─────▼─────┐
            │  App #1   │           │  App #2   │   (Stateless, 무상태)
            └─────┬─────┘           └─────┬─────┘
                  └───────────┬───────────┘
              ┌───────────────┼────────────────┐
        ┌─────▼─────┐   ┌─────▼─────┐    ┌──────▼──────┐
        │   Redis   │   │   MySQL   │    │  PG (Mock)  │
        │ 재고/멱등/락 │   │주문/결제/원장│    │ 인터페이스만 │
        └───────────┘   └───────────┘    └─────────────┘
```

- **App 서버는 완전 무상태(stateless)** → 정합성·공정성은 Redis/MySQL 등 공유 저장소가 보장.
- 재고의 **권위 있는 카운터(authoritative counter)는 Redis** (버스트 구간), MySQL은 영속·정합성 backstop.
- PG는 실제 연동 없이 **인터페이스(`PaymentGateway`)로 추상화**, Mock 구현으로 성공/실패/지연 시뮬레이션.

---

## 4. 도메인 모델 / ERD (주문·결제 중심)

```
product (상품)
 ├─ id, name, price, checkin_at, checkout_at, total_stock(=10), status

stock (재고 — 정합성 원장)
 ├─ product_id(PK/FK), total_qty, sold_qty, version(낙관락)

booking_order (주문)
 ├─ id, product_id, user_id, idempotency_key(UNIQUE), status(PENDING/PAID/FAILED/CANCELED)
 ├─ total_amount, created_at, updated_at

payment (결제)
 ├─ id, order_id(FK), status, total_amount, requested_at, completed_at

payment_detail (결제 수단별 분할 — 복합결제 표현)
 ├─ id, payment_id(FK), method(CREDIT_CARD/Y_PAY/Y_POINT), amount, pg_tx_id, status

user_point (사용자 포인트)
 ├─ user_id(PK), balance, version

point_history (포인트 변동 이력 — 보상/감사)
 ├─ id, user_id, order_id, amount(±), type(USE/REFUND), created_at

idempotency (멱등성 기록)  ※ 1차는 Redis, 영속 backstop은 테이블
 ├─ idempotency_key(PK), order_id, response_snapshot, created_at
```

- `idempotency_key` **UNIQUE 제약**으로 DB 레벨 중복 차단 (Redis 누락 시 backstop).
- `payment` ↔ `payment_detail` 1:N → **복합 결제**를 자연스럽게 표현.
- `stock`에 `version`(낙관락) + `sold_qty <= total_qty` CHECK/조건부 UPDATE → 초과판매 방지 최후 방어선.

---

## 5. API 설계

### 5.1 `GET /api/checkout`
- 입력: `productId`, (인증 생략이므로) `userId` 파라미터
- 처리: 상품 정보 + 현재 재고 가시값 + 사용자 가용 포인트 조회 (읽기 전용)
- 응답: 상품(명칭/가격/입·퇴실 시간), 가용 포인트, 적용 가능한 결제 수단/조합

### 5.2 `POST /api/bookings`
- 헤더: `Idempotency-Key` (필수)
- 바디: `productId`, `userId`, `payments[]`(method, amount), 합계
- 처리 순서:
  1. **멱등성 체크** (Redis SETNX → 중복 시 기존 결과 반환)
  2. **결제 수단 조합 검증** (신용카드+Y페이 혼용 불가 등)
  3. **재고 선점** (Redis 원자 차감, 실패 시 즉시 SOLD_OUT 응답)
  4. **결제 실행** (포인트 차감 → 카드/Y페이 PG 호출)
  5. 성공 → 주문 PAID 확정·DB 영속 / 실패 → **보상(재고·포인트 복원)** 후 FAILED
- 응답: 주문 결과 + 멱등 키 매핑 저장

---

## 6. 핵심 기능별 설계

### 6.1 재고 정합성 및 공정성 ★
**전략: Redis 원자 연산(Lua) 기반 선점 + MySQL 조건부 UPDATE backstop**

- 프로모션 시작 시 재고 10개를 Redis 키에 적재(`stock:{productId}=10`).
- 요청마다 **Lua 스크립트로 "재고>0 확인 + DECR"을 원자 처리** → Redis 단일 스레드 특성상 2대 서버에서도 **전역 직렬화**되어 초과판매 불가.
- DECR 성공자만 결제 단계 진입. 실패 시 즉시 마감 응답.
- 최종 확정 시 MySQL `UPDATE stock SET sold_qty=sold_qty+1 WHERE sold_qty < total_qty` (영향 행 0이면 롤백) → DB 레벨 2차 방어.
- **공정성**: Redis 원자 연산이 도착 순서대로 직렬 처리(선착순). 추가로 폭주 완화가 필요하면 **대기열(Redis List/Sorted Set) 기반 선입선출** 도입 검토 — 모든 서버 요청을 단일 큐에 줄세워 동등 순서 보장.
- **검토 대안 (→ DECISIONS.md)**: DB 비관락(`FOR UPDATE`) / 낙관락 / 분산락 — 1000 TPS에서 락 경합·throughput 한계 비교.

### 6.2 고가용성 (TPS 버스트 대응) ★
- 부하의 90%가 "재고 차감" 한 점에 집중 → **Redis 인메모리 원자 연산으로 흡수**, DB 쓰기는 확정자 ≤10건으로 최소화.
- **무상태 앱 2대 + LB**로 수평 분산. (단, 인프라 증설 제한 가정 → 효율 우선)
- **Fast-fail**: 재고 소진 후 요청은 Redis 단계에서 즉시 차단(early return) → 하위 자원 보호.
- 결제(외부 PG)는 **타임아웃 + 서킷 브레이커(Resilience4j)** 로 장애 격리.
- 커넥션 풀(HikariCP) 적정 사이징 + Redis 풀 튜닝.
- (옵션) 비동기 주문 영속화(아웃박스/큐) — 인프라 비용 대비 효과를 DECISIONS.md에 평가 후 채택 여부 결정.

### 6.3 멱등성 처리 ★
- 클라이언트가 `Idempotency-Key` 헤더 제공.
- 1차: **Redis `SETNX key`** (처리중 마킹, TTL). 이미 존재 시 진행 중/완료 결과 반환.
- 2차(영속 backstop): `booking_order.idempotency_key` **UNIQUE 제약** → Redis 유실에도 DB가 중복 INSERT 거부.
- 완료 응답을 키에 스냅샷 저장 → 재요청 시 동일 응답 반환(at-most-once 결제 보장).

### 6.4 결제 확장성 ★
**전략: 전략 패턴(Strategy) + 결제 수단 레지스트리 + 조합 검증 정책**

```
PaymentMethod (enum): CREDIT_CARD, Y_PAY, Y_POINT
PaymentProcessor (interface): supports(method), process(ctx), cancel(ctx)
  ├─ CreditCardProcessor
  ├─ YPayProcessor
  └─ YPointProcessor
PaymentOrchestrator: payments[] 수신 → 조합 검증 → 각 Processor 위임 → 부분실패 시 보상
PaymentCombinationPolicy: 허용 조합 규칙 (카드+Y페이 혼용 불가 등)
```
- **새 결제 수단 추가 = Processor 구현체 1개 추가** → Booking API/오케스트레이터 로직 무수정 (OCP).
- 조합 규칙은 정책 객체/설정으로 외부화 → 규칙 변경이 코드 분기 수정으로 번지지 않음.
- 복합 결제는 `payment_detail` N건으로 표현, 금액 합계 검증.

### 6.5 장애 대응 및 예외 처리 ★
- **Redis 장애 Fallback**:
  - 재고는 **정합성 > 가용성** 원칙. Redis 불가 시 → MySQL 비관락 경로로 **degrade(throughput↓, 정합성 유지)**. 또는 fail-closed(판매 일시중단)로 초과판매 원천 차단.
  - 멱등성은 DB UNIQUE 제약으로 backstop 동작 → Redis 없어도 중복 결제 차단 유지.
  - 서킷 브레이커로 Redis 호출 격리, 자동 복구 감지.
  - (두 옵션의 트레이드오프를 DECISIONS.md에 명시 후 택1)
- **결제 실패 (한도 초과 등)**:
  - 결제 단계는 **선점→실행→확정** 사가(SAGA) 흐름. 실패 시 **보상 트랜잭션**으로 재고 복원 + 사용 포인트 환불(`point_history` REFUND).
  - 복합 결제 부분 실패(포인트 차감 성공 + 카드 실패) → 차감분 전부 롤백 후 FAILED 확정.
  - PG Mock은 성공/한도초과/타임아웃/네트워크오류를 주입 가능하게 설계.

---

## 7. DECISIONS.md 작성 항목 (쟁점 목록)
프로젝트가 명시한 "왜 이 선택을 했는지"가 핵심 평가 → 아래 쟁점을 상황/선택지/근거(트레이드오프) 형식으로 작성.
1. 재고 정합성·공정성: Redis 원자연산 vs DB 락 vs 대기열 — 정합성·throughput·공정성 비교
2. Redis 장애 Fallback: degrade(DB락) vs fail-closed — 정합성/가용성 선택
3. 멱등성: Redis SETNX + DB UNIQUE 이중화 근거
4. 결제 확장성: 전략 패턴 + 정책 외부화로 OCP 달성
5. 고가용성: 인메모리 카운터로 부하 집중점 흡수, fast-fail, 서킷브레이커
6. 추가 인프라(k6/Docker Compose 등) 도입 사유 + 비용 대비 효과
7. 스택 선택 근거: Java 21(LTS) + Spring Boot 3.5.x + JPA + MySQL 8.4(LTS) + Lombok + Kotlin Gradle DSL (버전 호환·빌드 DSL 트레이드오프 포함)

---

## 8. 구현 단계 (마일스톤)

| 단계 | 작업 | 산출물 |
|------|------|--------|
| **0. 부트스트랩** | Gradle 프로젝트, Docker Compose(앱2+Nginx+MySQL+Redis), Flyway DDL | 실행 가능한 골격 |
| **1. 도메인/DB** | 엔티티·리포지토리, ERD, 초기 데이터(상품 1개·재고 10) | 스키마 확정 |
| **2. Checkout API** | 상품+포인트 조회 | GET API |
| **3. 재고 선점** | Redis Lua 원자 차감 + MySQL 조건부 UPDATE backstop | 정합성 코어 |
| **4. 결제 모듈** | Strategy 패턴 Processor + Orchestrator + 조합 정책 + PG Mock | 확장성 구조 |
| **5. Booking API** | 멱등성 → 검증 → 선점 → 결제 → 확정/보상 통합 | POST API |
| **6. 장애 대응** | Redis fallback, 서킷브레이커, 보상 트랜잭션 | 안정성 |
| **7. 테스트** | 동시성(초과판매 0), 멱등성, 부하(500~1000 TPS) | 검증 리포트 |
| **8. 문서화** | README(아키텍처/시퀀스/ERD/실행법), DECISIONS.md | 제출물 |

---

## 9. 테스트 전략 (정합성 증명 중심)
- **동시성 테스트**: N(>>10) 스레드 동시 Booking → 정확히 10건 PAID, 초과판매 0, 미달 0 단언 (JUnit + Awaitility).
- **멱등성 테스트**: 동일 `Idempotency-Key` 연속/동시 요청 → 결제 1회·동일 응답.
- **결제 조합 테스트**: 허용/불허(카드+Y페이) 조합, 복합결제 금액 합계, 부분 실패 보상.
- **장애 테스트**: Redis 다운 시 fallback 동작, PG 타임아웃/한도초과 시 재고·포인트 복원.
- **부하 테스트**: k6로 50 TPS 평시 + 500~1000 TPS 버스트 1~5분 재현, 에러율·지연 측정.

---

## 10. 디렉토리 구조 (예정)
```
.
├── README.md
├── DECISIONS.md
├── docs/
│   ├── plan.md
│   ├── architecture.md (시퀀스 다이어그램·플로우차트)
│   └── erd.md / schema.sql
├── docker-compose.yml
├── nginx/nginx.conf
├── build.gradle.kts          (Kotlin Gradle DSL)
├── settings.gradle.kts
└── src/
    ├── main/java/.../
    │   ├── product/        (Checkout)
    │   ├── stock/          (Redis 원자차감 + DB backstop)
    │   ├── booking/        (멱등성·주문 오케스트레이션)
    │   ├── payment/        (Strategy Processor·Orchestrator·정책·PG Mock)
    │   ├── point/
    │   └── common/         (서킷브레이커·예외·멱등성 인프라)
    └── test/java/.../      (동시성·멱등·부하·장애 테스트)
```

---

## 11. 제출 전 최종 체크리스트
- [ ] 코드 수정 없이 `docker-compose up` 으로 앱 2대 분산 환경 기동
- [ ] GET Checkout / POST Booking 동작
- [ ] 동시성 테스트로 초과판매 0 / 미달 0 증명
- [ ] 멱등성·결제 조합·복합결제·보상 테스트 통과
- [ ] Redis 장애 fallback·결제 실패 대응 동작
- [ ] 부하 테스트(500~1000 TPS) 결과 첨부
- [ ] README: 아키텍처·실행법·시퀀스 다이어그램·ERD/DDL
- [ ] DECISIONS.md: 쟁점별 상황/선택지/근거(트레이드오프)
- [ ] **회사명·서비스·조직 식별 내용 미포함** 점검
- [ ] Git Public Repo + `docs/` 포함
```

# 선착순 예약/결제 플랫폼 — 설계 명세 (Design Spec)

> 특정 시각(00시)에 오픈되는 **초특가 숙소 상품(10개 한정)** 에 대한 **선착순 예약·결제 시스템**.
> 애플리케이션 서버 **2대 이상 분산 환경**. 인프라 증설은 제한적이라고 가정한다.

본 문서는 구현 전 확정된 설계 명세다. 상세 단계 계획은 후속 implementation plan에서 다룬다.

---

## 1. 목표와 성공 기준

| 목표 | 성공 기준 (측정 가능) |
|------|------|
| 재고 정합성 | 분산·버스트 상황에서 정확히 10건만 PAID. **초과판매 0, 미달 0** |
| 공정성 | 도착 순서대로 직렬 처리(선착순). 한 사용자가 재고를 독식 불가(1인 1구매) |
| 멱등성 | 동일 요청 중복 시 결제 1회·동일 응답 |
| 1인 1구매 | 같은 사용자는 동일 상품을 1개만 구매. 2개째 요청은 거부 |
| 고가용성 | 평시 50 TPS, 00시 1~5분 500~1000 TPS 버스트에서 시스템 붕괴 없음 |
| 결제 확장성 | 새 결제 수단 추가 시 Booking 비즈니스 로직 무수정(구현체 1개 추가) |
| 장애 대응 | Redis 장애·결제 실패에서도 데이터 일관성 유지 |

---

## 2. 확정된 핵심 설계 결정

본 절의 4개 결정이 아키텍처의 키스톤이다. 트레이드오프 서술은 DECISIONS.md에 옮긴다.

### 결정 1 — 공정성 = 순수 선착순 (Redis 원자 DECR)
- 모든 서버의 요청이 단일 Redis로 모여 Lua 스크립트로 직렬 처리된다. Redis 단일 스레드 특성상 전역 직렬화 → 도착 순서대로 선착순.
- "동등한 확률"은 **도착 순서 기준 공정성 + 1인 1구매 독식 방지**로 해석·충족한다. 별도 대기열/추첨은 도입하지 않는다(복잡도·지연 대비 이득 부족).

### 결정 2 — Redis 장애 Fallback = DB락으로 degrade
- Redis 불가 시 서킷브레이커 OPEN → MySQL `SELECT ... FOR UPDATE` + 조건부 UPDATE 경로로 전환.
- throughput은 하락하나 판매 지속·정합성 유지(가용성 우선). 재고가 10개로 작아 DB락 경합은 감당 가능.
- 멱등성·1인 1구매는 DB 제약(UNIQUE/PK)이 backstop으로 동작 → Redis 없어도 보장.

### 결정 3 — 1인 1구매 = Redis SET 원자 가드 + DB PK backstop
- Redis 재고 선점 Lua에 **구매자 집합(`buyers:{productId}`) 검사·기록**을 합쳐 한 원자 단위로 처리.
- DB는 `purchase_lock(user_id, product_id)` PK로 backstop. 결제 실패 시 보상으로 해제 → 재시도 허용.

### 결정 4 — 구현 범위 = 풀세트
- 동작 코드 + `docker-compose`(앱 2대 + Nginx LB + MySQL + Redis) + 동시성·멱등·장애 테스트 + k6 부하 스크립트 + README/DECISIONS.

---

## 3. 시스템 아키텍처

```
                       ┌─────────────┐
        Clients  ───►  │  Nginx (LB) │  (라운드로빈)
                       └──────┬──────┘
                  ┌───────────┴───────────┐
            ┌─────▼─────┐           ┌─────▼─────┐
            │  App #1   │           │  App #2   │   (Stateless)
            └─────┬─────┘           └─────┬─────┘
                  └───────────┬───────────┘
              ┌───────────────┼────────────────┐
        ┌─────▼─────┐   ┌─────▼─────┐    ┌──────▼──────┐
        │   Redis   │   │   MySQL   │    │  PG (Mock)  │
        │재고/멱등/락 │   │주문/결제/원장│    │ 인터페이스만 │
        │/구매자집합 │   │            │    │             │
        └───────────┘   └───────────┘    └─────────────┘
```

- **앱은 완전 무상태(stateless)**. 정합성·공정성·중복차단은 모두 공유 저장소(Redis/MySQL)가 보장한다.
- 재고 **권위 카운터는 Redis**(버스트 흡수), MySQL은 영속·정합성 backstop.
- PG는 실제 연동 없이 `PaymentGateway` 인터페이스로 추상화, Mock 구현이 성공/한도초과/타임아웃/네트워크오류를 주입.

---

## 4. 도메인 모델 / ERD

```
product (상품)
 └ id, name, price, checkin_at, checkout_at, total_stock(=10), status

stock (재고 — 정합성 원장)
 └ product_id(PK/FK), total_qty, sold_qty, version(낙관락)
   ※ 조건부 UPDATE: sold_qty < total_qty 일 때만 +1 (초과판매 최후 방어선)

booking_order (주문)
 └ id, product_id, user_id, idempotency_key(UNIQUE),
   status(PENDING/PAID/FAILED/CANCELED), total_amount, created_at, updated_at

payment (결제)
 └ id, order_id(FK), status, total_amount, requested_at, completed_at

payment_detail (결제 수단별 분할 — 복합결제 표현, payment 1:N)
 └ id, payment_id(FK), method(CREDIT_CARD/Y_PAY/Y_POINT), amount, pg_tx_id, status

user_point (사용자 포인트)
 └ user_id(PK), balance, version(낙관락)

point_history (포인트 변동 이력 — 보상/감사)
 └ id, user_id, order_id, amount(±), type(USE/REFUND), created_at

purchase_lock (1인 1구매 backstop)   ★ 신규
 └ user_id, product_id   PK(user_id, product_id)
   ※ 주문 확정 시 INSERT, 보상 시 DELETE → 실패 유저 재시도 허용

idempotency (멱등성 기록 — 1차 Redis, 영속 backstop은 테이블)
 └ idempotency_key(PK), order_id, response_snapshot, created_at
```

- `booking_order.idempotency_key` **UNIQUE** → 멱등성 DB backstop.
- `purchase_lock` **PK(user_id, product_id)** → 1인 1구매 DB backstop.
- `payment` ↔ `payment_detail` 1:N → 복합 결제를 자연스럽게 표현.
- `stock.version` 낙관락 + 조건부 UPDATE → 초과판매 최후 방어선.

---

## 5. API 설계

### 5.1 `GET /api/checkout`
- 입력: `productId`, `userId`(인증 생략 가정)
- 처리: 상품 정보 + 현재 재고 가시값 + 사용자 가용 포인트 + 적용 가능한 결제 수단/조합 조회(읽기 전용)
- 응답: 상품(명칭/가격/입·퇴실 시간), 가용 포인트, 허용 결제 조합, `alreadyPurchased`(이미 구매 여부)

### 5.2 `POST /api/bookings`
- 헤더: `Idempotency-Key`(필수)
- 바디: `productId`, `userId`, `payments[]`(method, amount), 합계
- 처리 순서:
  1. **멱등성 체크** — Redis `SETNX`. 존재 시 진행중/완료 결과 반환
  2. **결제 조합 검증** — 신용카드+Y페이 혼용 불가 등 정책
  3. **재고 선점 + 1인 1구매 가드** — Redis Lua 단일 원자 연산
     (이미 구매 → `ALREADY_PURCHASED` / 재고 소진 → `SOLD_OUT`)
  4. **결제 실행** — 포인트 차감 → 카드/Y페이 PG 호출
  5. 성공 → 주문 PAID 확정·DB 영속(`purchase_lock` INSERT 포함)
     / 실패 → **보상**(재고·포인트·구매자집합·purchase_lock 복원) 후 FAILED
- 응답: 주문 결과 + 멱등 키 응답 스냅샷 저장

---

## 6. 핵심 기능별 설계

### 6.1 재고 정합성 · 공정성 · 1인 1구매 (통합 원자 가드)
재고 선점과 1인 1구매를 **하나의 Lua 스크립트**로 원자 처리한다.

```lua
-- KEYS[1]=stock:{productId}  KEYS[2]=buyers:{productId}  ARGV[1]=userId
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end  -- ALREADY_PURCHASED
local n = tonumber(redis.call('GET', KEYS[1]))
if n == nil or n <= 0 then return 0 end                               -- SOLD_OUT
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1                                                              -- RESERVED
```

- 단일 스레드 + 단일 스크립트 = **중복확인 + 재고확인 + 차감 + 구매자기록**이 분리 불가능한 원자 단위. 2대 서버·동시 더블서밋에도 레이스 없음.
- 프로모션 시작 시 `stock:{productId}=10` 적재, `buyers:{productId}` 초기화.
- 선점 성공자만 결제 단계 진입. 실패 시 즉시 응답(fast-fail).
- 최종 확정 시 MySQL `UPDATE stock SET sold_qty=sold_qty+1 WHERE product_id=? AND sold_qty < total_qty` (영향 행 0이면 롤백) → DB 2차 방어.

### 6.2 고가용성 (TPS 버스트 대응)
- 부하의 대부분이 "재고 차감" 한 점에 집중 → **Redis 인메모리 원자 연산으로 흡수**. DB 쓰기는 확정자 ≤10건으로 최소화.
- 무상태 앱 2대 + Nginx LB로 수평 분산(인프라 증설 제한 가정 → 효율 우선).
- **Fast-fail**: 재고 소진/이미 구매 요청은 Redis 단계에서 즉시 차단 → 하위 자원 보호.
- 외부 PG 호출은 **타임아웃 + 서킷브레이커(Resilience4j)** 로 장애 격리.
- HikariCP·Redis 커넥션 풀 적정 사이징.

### 6.3 멱등성
- 클라이언트가 `Idempotency-Key` 헤더 제공.
- 1차: Redis `SETNX`(처리중 마킹, TTL). 존재 시 진행중/완료 응답 반환.
- 2차(영속 backstop): `booking_order.idempotency_key` **UNIQUE** → Redis 유실에도 중복 INSERT 거부.
- 완료 응답을 키에 스냅샷 저장 → 재요청 시 동일 응답(at-most-once 결제).
- **멱등성 vs 1인 1구매 직교성**: 같은 키 재요청 = 캐시 응답 / 다른 키·같은 상품 = `ALREADY_PURCHASED`.

### 6.4 결제 확장성 (Strategy 패턴 + 조합 정책)
```
PaymentMethod (enum): CREDIT_CARD, Y_PAY, Y_POINT
PaymentProcessor (interface): supports(method), process(ctx), cancel(ctx)
  ├ CreditCardProcessor
  ├ YPayProcessor
  └ YPointProcessor
PaymentOrchestrator: payments[] → 조합 검증 → 각 Processor 위임 → 부분실패 시 보상
PaymentCombinationPolicy: 허용 조합 규칙 (카드+Y페이 혼용 불가 등)
```
- **새 결제 수단 = Processor 구현체 1개 추가** → Booking/오케스트레이터 로직 무수정(OCP).
- 조합 규칙은 정책 객체/설정으로 외부화 → 규칙 변경이 분기 수정으로 번지지 않음.
- 복합 결제는 `payment_detail` N건 + 금액 합계 검증.
- 허용 조합: `(신용카드+포인트)` 또는 `(Y페이+포인트)`. **신용카드+Y페이 혼용 불가.**

### 6.5 장애 대응 · 예외 처리
- **Redis 장애 Fallback (결정 2)**: 서킷브레이커 OPEN → MySQL 비관락 경로로 degrade(throughput↓, 정합성 유지). 멱등성·1인 1구매는 DB 제약이 backstop. 자동 복구 감지 시 Redis 경로 복귀.
- **결제 실패 (한도 초과 등)**: 선점→실행→확정 SAGA 흐름. 실패 시 **보상 트랜잭션**:
  - 재고 복원(Redis `INCR` + DB sold_qty 롤백)
  - 사용 포인트 환불(`point_history` REFUND)
  - 구매자집합 해제(`SREM buyers:{productId} userId`)
  - `purchase_lock` DELETE → 재구매 허용
- 복합 결제 부분 실패(포인트 성공 + 카드 실패) → 차감분 전부 롤백 후 FAILED 확정.
- PG Mock은 성공/한도초과/타임아웃/네트워크오류 주입 가능.

### 6.6 일관성 보강 (reconciliation)
- crash로 Redis 선점 후 DB 영속 전 중단되는 경우 대비: `buyers`·재고 키에 정책적 TTL + 미확정 주문 정리(배치/조회 시 보정). 상세는 implementation plan에서 확정.

---

## 7. 검증 전략 (정합성 증명 중심)

| 테스트 | 단언 |
|------|------|
| 동시성 | N(≫10) 스레드 동시 Booking → 정확히 10건 PAID, 초과판매 0·미달 0 (JUnit5 + Awaitility) |
| 멱등성 | 동일 `Idempotency-Key` 연속/동시 요청 → 결제 1회·동일 응답 |
| 1인 1구매 | 같은 user_id로 다른 키 2회·동시 더블서밋 → 1건만 성공, 나머지 `ALREADY_PURCHASED` |
| 결제 조합 | 허용/불허(카드+Y페이) 조합, 복합결제 금액 합계, 부분 실패 보상 |
| 장애 | Redis 다운 시 DB락 fallback 동작, PG 타임아웃/한도초과 시 재고·포인트·락 복원 |
| 부하 | k6로 평시 50 TPS + 버스트 500~1000 TPS(1~5분) 재현, 에러율·지연 측정 |

테스트 인프라: JUnit5 + Testcontainers(MySQL/Redis) + Awaitility + k6.

---

## 8. 산출물

1. **동작 가능한 소스 코드** — 코드 수정 없이 `docker-compose up`으로 앱 2대 분산 기동.
2. **README.md** — 아키텍처 설명·실행 방법, 시퀀스 다이어그램/플로우차트, ERD 또는 DDL(주문/결제 중심).
3. **DECISIONS.md** — 주요 기술 쟁점 선택 근거, 라이브러리 도입 사유·문제 해결 전략.
4. `docs/` 디렉토리에 설계 문서 포함.

### DECISIONS.md 쟁점 목록
1. 공정성·정합성: Redis 원자연산 vs DB락 vs 대기열 — 정합성·throughput·공정성 비교
2. Redis 장애 Fallback: DB락 degrade vs fail-closed — 가용성/정합성 선택 근거
3. 1인 1구매: Redis SET 원자가드 + DB PK backstop, 보상 시 해제 근거
4. 멱등성: Redis SETNX + DB UNIQUE 이중화 근거
5. 결제 확장성: Strategy 패턴 + 정책 외부화로 OCP 달성
6. 고가용성: 인메모리 카운터로 부하 집중점 흡수, fast-fail, 서킷브레이커
7. 추가 인프라(k6/Docker Compose 등) 도입 사유 + 비용 대비 효과
8. 스택 선택: Java 21(LTS) + Spring Boot 3.5.x + JPA + MySQL 8.4(LTS) + Lombok + Kotlin Gradle DSL

---

## 9. 기술 스택 (확정)

| 항목 | 선택 |
|------|------|
| 언어 | Java 21 (LTS) |
| 프레임워크 | Spring Boot 3.5.x |
| 영속성 | Spring Data JPA(Hibernate) + MySQL 8.4(LTS) |
| Cache/Atomic | Redis 7 (재고 카운터·구매자집합·멱등성·분산락) |
| 분산락/원자 | Redis Lua 직접 (옵션: Redisson 검토) |
| 보일러플레이트 | Lombok |
| 빌드 | Gradle (Kotlin DSL) |
| 컨테이너 | Docker Compose (앱 2 + Nginx + MySQL + Redis) |
| 부하 테스트 | k6 |
| API 문서 | OpenAPI (springdoc) |
| DDL | Flyway 또는 schema.sql (명시 관리) |

---

## 10. 범위 제외 (Out of Scope)
- 실제 PG 연동(인터페이스·Mock으로 대체)
- 회원 인증·로그인 보안(평가 범위 외)
- 무한 수평 확장 전제(인프라 증설 제한 가정)

# 설계 결정 기록 (Architecture Decision Records)

midnight-deal 플랫폼 구현 과정에서 중요하게 판단한 설계 쟁점 11개를 기록한다.  
각 항목은 **상황 → 선택지 → 판단 근거(트레이드오프)** 순서로 서술한다.

---

## 쟁점 1. 재고 정합성·공정성: Redis 원자 Lua vs DB 락 vs 대기열

### 상황
한정 수량(10개) 상품에 수천 명이 동시에 접근하는 심야 타임세일 시나리오. 재고 초과판매를 막으면서 요청 순서에 따른 공정한 선착순을 보장해야 한다.

### 선택지
| 방식 | 정합성 | 공정성 | 처리량 |
|------|--------|--------|--------|
| DB 비관락 (SELECT FOR UPDATE) | 높음 | 낮음(락 경합) | 낮음 |
| DB 낙관락 (version) | 높음 | 낮음(재시도 경쟁) | 중간 |
| Redis Lua 원자 스크립트 | 높음 | **높음(단일 큐)** | **높음** |
| 대기열(Queue/Kafka) | 높음 | 높음 | 높음(복잡도↑) |

### 왜 이렇게 판단했는지 (트레이드오프)
- Redis 단일 스레드 모델 위에서 Lua 스크립트(`reserve.lua`)를 실행하면 **SISMEMBER → GET → DECR → SADD** 4개 명령이 원자적으로 처리된다.
- 요청이 Redis에 도달한 순서대로 직렬화되므로 **순수 선착순**이 자연스럽게 구현된다.
- DB락 대비 처리량이 크게 높고, 대기열 대비 구현 복잡도가 낮다.
- **단점**: Redis가 단일 장애점이 된다. → 쟁점 2에서 Fallback으로 완화.

---

## 쟁점 2. Redis 장애 Fallback: DB 락 degrade vs fail-closed

### 상황
Redis 장애 시 재고 선점 경로가 완전히 차단되면 서비스 가용성이 0이 된다. 그러나 Fallback 경로가 정합성을 보장하지 못하면 초과판매 위험이 생긴다.

### 선택지
- **fail-closed**: Redis 장애 시 즉시 503 반환. 정합성 최우선.
- **DB 락 degrade**: Redis 장애 시 `DbStockFallback`(SELECT FOR UPDATE) 경로로 자동 전환.

### 왜 이렇게 판단했는지 (트레이드오프)
- **DB 락 degrade를 선택**했다. (`application.yml: stock.fallback-enabled: true`)
- Resilience4j 서킷브레이커(`redisStock`)가 Redis 예외를 감지하면 `reserveViaDb` fallbackMethod를 호출한다.
- `DbStockFallback.reserve()`는 `SELECT FOR UPDATE`로 `stock` 테이블을 비관락 획득 후 `sold_qty < total_qty`를 검사하므로 초과판매가 발생하지 않는다.
- 처리량은 Redis 정상 경로 대비 낮아지지만, 타임세일 특성상 **재고 소진 후에는 어차피 409**가 대부분이므로 실질적 영향은 제한적이다.
- fail-closed는 Redis 순간 재시작 같은 일시 장애에도 서비스가 완전히 중단되어 사용자 경험이 크게 나빠진다.

---

## 쟁점 3. 1인 1구매 보장: Redis SET 원자 가드 + DB PK backstop + 보상 시 정확한 해제

### 상황
동일 사용자가 여러 디바이스·탭에서 동시에 구매를 시도할 수 있다. 그리고 **보상(release) 시 직전 성공 구매를 오염시키지 않아야** 한다.

### 선택지
- DB `purchase_lock` PK 단독 사용 → DB 왕복 지연이 병목.
- Redis `buyers:{pid}` SET 원자 가드 단독 → Redis 장애 시 누락.
- **Redis SET 원자 가드 + DB PK 이중 backstop** → 두 계층 모두 방어.

### 왜 이렇게 판단했는지 (트레이드오프)
- `reserve.lua`에서 `SISMEMBER buyers:{pid} userId == 1`이면 즉시 `-1(ALREADY_PURCHASED)`를 반환한다. Redis 레벨에서 DB 왕복 없이 차단.
- DB 레벨에서는 `purchase_lock(user_id, product_id)` 복합 PK가 중복 INSERT를 막는다.
- **핵심 정확성 포인트**: `BookingService`는 `ReserveResult.RESERVED`를 받은 경우(이번 요청이 선점한 경우)에만 `release()`를 호출한다. `SOLD_OUT`/`ALREADY_PURCHASED`는 이번 요청이 선점하지 않았으므로 절대 `release`하지 않는다. 특히 `ALREADY_PURCHASED` 사용자는 이전 성공 구매로 `buyers` set에 남아 있으므로, 잘못 `release`하면 그 사용자가 `SREM`되고 재고가 `INCR`되어 데이터가 오염된다.
- `release.lua`도 `SISMEMBER` 후 `SREM + INCR`으로 원자 처리하여 이중 release를 방어한다.

---

## 쟁점 4. 멱등성: Redis SETNX + DB UNIQUE 이중화

### 상황
네트워크 재전송이나 클라이언트 재시도로 동일 `Idempotency-Key`가 여러 번 도달할 수 있다. 한 번만 처리하고 나머지는 동일 응답을 반환해야 한다.

### 선택지
- DB `idempotency_key UNIQUE` 단독 → 분산 환경에서 레이스 컨디션 발생 가능.
- Redis SETNX 단독 → Redis 재시작 시 마크 소멸.
- **Redis SETNX(idem:mark) + DB UNIQUE(booking_order.idempotency_key) + 응답 스냅샷(idem:res)** 3중 구조.

### 왜 이렇게 판단했는지 (트레이드오프)
- `IdempotencyService.tryBegin()`은 `SETNX idem:mark:{key}`로 원자적 선점을 수행한다. 두 요청이 동시에 들어와도 하나만 `true`를 받는다.
- `booking_order.idempotency_key`에 `UNIQUE` 제약(V1__schema.sql: `uq_idem`)이 있어 Redis 장애 후 DB 레벨에서 이중 삽입을 차단한다.
- 처리 완료 후 `idem:res:{key}`에 응답 JSON을 스냅샷으로 저장해 재요청 시 즉시 반환한다.
- TTL은 10분으로 설정해 무한 누적을 방지하면서 실질적 재시도 윈도우를 커버한다.
- **단점**: Redis 와 DB 간 순간 비동기 갭이 있으나, 마크(Redis) → 처리 → 스냅샷(Redis) → DB UNIQUE 순서를 지켜 실질적 위험을 제거했다.

---

## 쟁점 5. 결제 확장성: Strategy + 조합정책 외부화(OCP), 포인트 보상 단일 책임

### 상황
현재 결제 수단은 `CREDIT_CARD`, `Y_PAY`, `Y_POINT` 세 가지이나, 향후 추가될 수 있다. 복합 결제(카드+포인트 등) 조합 규칙도 변경 가능성이 있다.

### 선택지
- 단일 결제 서비스에 switch/if 분기 → 수단 추가 시 기존 코드 수정 필요(OCP 위반).
- **Strategy 패턴(`PaymentProcessor` 인터페이스) + `PaymentCombinationPolicy` 외부화** → OCP 준수.

### 왜 이렇게 판단했는지 (트레이드오프)
- `PaymentProcessor` 인터페이스를 구현한 `CreditCardProcessor`, `YPayProcessor`, `YPointProcessor`가 각각 `supports(method)` 메서드로 라우팅된다. 새 수단 추가 시 구현체만 추가하면 된다.
- `PaymentCombinationPolicy`가 허용/금지 조합 규칙을 단독으로 보유한다. 규칙 변경 시 이 클래스만 수정한다. (CREDIT_CARD+Y_PAY 혼용 금지, 동일 수단 중복 라인 금지)
- `PaymentOrchestrator`가 순차 결제, 부분 실패 보상(이미 성공한 수단 취소·환불), 포인트 환불 단일 책임을 담당한다. 포인트 환불은 PG 취소와 분리되어 `PointService.refund()`가 책임진다.
- **단점**: 클래스 수가 늘어난다. 그러나 각 클래스가 명확한 단일 책임을 지므로 테스트와 유지보수가 쉬워진다.

---

## 쟁점 6. 고가용성: 인메모리 카운터 흡수, fast-fail, 서킷브레이커

### 상황
타임세일 순간 수천 req/s가 몰린다. DB·Redis 커넥션이 병목이 되면 전체 서비스가 느려진다.

### 선택지
- 단순 스케일아웃 → 비용 증가, 급격한 스파이크 대응 한계.
- **Redis 인메모리 카운터 흡수 + fast-fail + Resilience4j 서킷브레이커** 조합.

### 왜 이렇게 판단했는지 (트레이드오프)
- Redis `stock:{pid}` 정수 카운터가 DB보다 수십 배 빠른 응답으로 재고 초과 요청을 즉시 흡수한다. 재고가 소진되면 대부분의 요청이 Lua 스크립트에서 `0(SOLD_OUT)`을 반환받아 DB에 도달하지 않는다.
- 재고가 0인 상태에서는 DB 쓰기가 발생하지 않으므로 커넥션 풀 소모가 최소화된다(fast-fail).
- Resilience4j `redisStock` 서킷브레이커(`sliding-window-size: 20, failure-rate-threshold: 50%`)가 Redis 장애를 감지하면 5초간 오픈 상태를 유지하며 DB fallback으로 전환, Redis 회복 후 half-open 3회 통과 시 재닫힘한다.
- **단점**: Redis 장애 시 DB fallback으로 전환되므로 처리량이 일시 저하된다. Redis 복제 구성이 없어 Redis 재시작 시 재고 카운터가 초기화될 수 있다(DB stock이 진실의 원천).

---

## 쟁점 7. 추가 인프라(Docker Compose / Nginx / k6 / Testcontainers) 비용 대비 효과

### 상황
로컬 실행과 부하 검증을 위한 인프라를 어느 수준까지 포함할지 결정해야 한다. 과도한 인프라 설정은 유지 비용을 높인다.

### 선택지
- 최소(앱만 실행, 외부 DB 연결) → 재현성 없음, 팀원 온보딩 어려움.
- **Docker Compose(MySQL+Redis+app1+app2+Nginx) + k6 + Testcontainers** 풀 구성.

### 왜 이렇게 판단했는지 (트레이드오프)
- **Docker Compose**: `docker compose up -d --build` 한 명령으로 2-인스턴스 + Nginx 로드밸런서 + DB + Redis가 모두 기동된다. 로컬에서 프로덕션 유사 환경을 즉시 재현할 수 있다.
- **Nginx**: 단일 포트(8080) 뒤에 app1/app2를 라운드로빈으로 숨겨 `Idempotency-Key` 중복 처리, Redis 공유 재고의 분산 정합성을 실제로 검증할 수 있다.
- **k6**: baseline(50 rps) → burst(1000 rps) 시나리오로 타임세일 스파이크를 재현하며 5xx 발생 여부를 단일 체크(`check: no 5xx`)로 확인한다. 4xx(매진/중복)는 정상으로 분류한다.
- **Testcontainers**: 테스트 실행 시 MySQL·Redis 컨테이너를 자동 기동해 외부 의존 없이 통합 테스트를 실행한다. CI 환경에서도 동일하게 동작한다.
- **단점**: Docker가 없는 환경에서는 실행할 수 없다. 빌드 이미지 크기가 증가한다.

---

## 쟁점 8. confirm() 순서: 외부 결제(PG 청구)를 마지막 실패 가능 단계로 배치 (orphan charge 제거)

### 상황
`BookingConfirmService.confirm()`은 단일 트랜잭션에서 (a) 주문 INSERT, (b) DB 재고 backstop(`confirmSold`), (c) 1인1구매 락 INSERT, (d) 외부 PG 청구(`orchestrator.pay`), (e) Payment 영속을 수행한다. 이전 구현은 **결제를 먼저** 한 뒤 재고 backstop·락 INSERT를 했다. 이 경우 PG에 청구가 끝난 뒤 재고 초과(`confirmSold==0`)나 락 PK 충돌이 발생하면, 트랜잭션은 롤백되지만 **카드 청구는 이미 외부 PG에 일어나 있는 "취소되지 않은 청구(orphan charge)"** 가 남는다. (트랜잭션 롤백은 외부 PG 상태를 되돌리지 못한다.)

### 선택지
- 결제 후 DB 검증(기존) + 실패 시 PG 보상 취소를 confirm 안에서 추가 호출 → 보상 자체가 또 실패할 수 있어 2차 orphan 위험.
- **모든 DB 자원을 결제 전에 선점·검증하고, PG 청구를 마지막 실패 가능 외부 단계로 배치**(채택).

### 왜 이렇게 판단했는지 (트레이드오프)
- 순서를 `주문 INSERT → confirmSold(즉시 조건부 UPDATE) → lockRepo.saveAndFlush(PK 충돌 동기 발생) → orchestrator.pay → Payment 영속 → markPaid`로 재배치했다.
- `confirmSold`는 `WHERE sold_qty<total_qty` 조건부 UPDATE라 즉시 실행되어 초과판매면 **청구 전에** 차단된다.
- `lockRepo.saveAndFlush(...)`는 `save`가 아니라 **flush를 강제**하므로 1인1구매 PK 충돌(`DataIntegrityViolationException`)이 청구 전에 동기적으로 표면화된다. (지연 flush였다면 커밋 시점=청구 후에 터졌을 것.)
- 따라서 **PG 청구 이후에는 실패할 DB 단계가 존재하지 않는다 → orphan charge 구조적으로 불가능.**
- `pay()`가 실패하면 orchestrator의 `compensate()`가 이미 성공한 포인트/PG 라인을 보상하고, `@Transactional` 롤백이 주문/재고/락을 원복하며, `BookingService`의 `catch`가 Redis 선점을 release한다.
- PK 충돌은 `DataIntegrityViolationException`으로 전파되어 `BookingService.catch(RuntimeException)` → release + rethrow → `ApiExceptionHandler`가 409로 매핑한다(청구 없음).
- **단점**: 재고/락을 먼저 점유하므로 결제 실패 시 잠깐 DB 자원을 점유했다가 롤백한다. 그러나 트랜잭션 수명이 매우 짧고, 청구-무취소 위험 제거가 훨씬 중요하다. 검증: `BookingPaymentIntegrityTest`.

---

## 쟁점 9. 결제 영속화: Payment + PaymentDetail durable 레코드 (정산/대사 근거)

### 상황
이전 구현은 결제 성공 후 `Payment`/`PaymentDetail` 행을 **남기지 않았다**. 정산·대사(reconciliation)·CS 조회를 위해 수단별 `pg_tx_id`를 가진 durable 레코드가 필요하다.

### 선택지
- 영속 생략(주문 상태만 PAID) → PG 청구와 우리 DB를 대조할 키가 없어 대사 불가.
- **`orchestrator.pay()` 성공 직후 동일 트랜잭션에서 `Payment`(+수단별 `PaymentDetail`) 영속**(채택).

### 왜 이렇게 판단했는지 (트레이드오프)
- `Payment`는 `@OneToMany(cascade=ALL)`로 `PaymentDetail`을 함께 영속한다. 각 디테일은 `method/amount/pgTxId/status="COMPLETED"`를 보유한다.
- `pg_tx_id`로 PG측 거래와 1:1 대조가 가능해 대사·환불 추적의 근거가 된다.
- **구현 주의**: `payment_detail.payment_id`는 스키마상 `NOT NULL`인데, 단방향 `@OneToMany + @JoinColumn`은 기본적으로 자식 INSERT 후 별도 UPDATE로 FK를 채워 초기 INSERT가 NOT NULL 제약을 위반했다. → `@JoinColumn(name="payment_id", nullable=false)`로 설정해 Hibernate가 **자식 INSERT에 FK를 포함**하도록 수정(별도 UPDATE 제거).
- 동일 트랜잭션에 두므로 결제는 성공했는데 영속만 누락되는 갭이 없다. (Payment 영속 실패는 곧 청구 후 DB 실패가 되지만, 영속은 PG 청구 직후 로컬 INSERT라 사실상 항상 성공하며 실패 시에도 단일 트랜잭션 내 보상으로 일관성을 유지한다.)

---

## 쟁점 10. 커넥션 풀 사이징: 500~1000 TPS 버스트 하의 HikariCP

### 상황
타임세일 순간 500~1000 TPS 버스트가 들어온다. 스펙은 "HikariCP·Redis 풀 적정 사이징"을 요구한다. 풀이 너무 크면 작은 DB가 커넥션 폭주로 죽고, 너무 작으면 풀 고갈로 스레드가 무한 대기한다.

### 선택지
- 기본값(maximum-pool-size=10) 방치 → 사이징 근거 부재.
- **버스트 대부분을 Redis가 흡수한다는 전제로 작은 DB 풀 + fast-fail 타임아웃**(채택).

### 왜 이렇게 판단했는지 (트레이드오프)
```yaml
spring.datasource.hikari:
  maximum-pool-size: 30     # 버스트 대부분을 Redis가 흡수 → 작은 DB 풀로 충분
  minimum-idle: 10          # 세일 오픈 직전 워밍업 유지로 콜드 스타트 지연 방지
  connection-timeout: 2000  # 풀 고갈 시 2s 빠른 실패 — 무한 대기로 스레드 점유 방지
```
- 재고가 소진되면 대부분의 요청은 Redis Lua에서 `SOLD_OUT`을 받아 **DB에 도달하지 않는다**(쟁점 6 fast-fail). 실제 DB 쓰기는 "성공 구매 = 재고 수(10)" 수준이라 30 커넥션이면 충분하다.
- `connection-timeout: 2000`으로 풀이 고갈돼도 2초 내 실패해 톰캣 스레드가 무한 점유되지 않는다.
- `minimum-idle: 10`으로 세일 오픈 직전 미리 커넥션을 데워두면 첫 요청의 콜드 스타트 지연을 피한다.
- **단점**: DB 사양이 더 큰 환경에서는 30이 보수적일 수 있다. 운영 부하 테스트(k6)로 재튜닝 대상.

---

## 알려진 한계 / 향후 개선

- **멱등 응답 스냅샷이 Redis-only**: 처리 결과 스냅샷(`idem:res:{key}`)을 durable `idempotency` 테이블이 아니라 Redis에만 저장한다. DB 커밋과 Redis 스냅샷 저장 사이에 인스턴스가 크래시하면, 재요청 시 멱등 마크(또는 DB UNIQUE)는 남아 있으나 스냅샷이 없어 **TTL 만료까지 409(DUPLICATE_REQUEST/IN_PROGRESS)** 가 반환될 수 있다. → 향후 `idempotency` 테이블에 응답 스냅샷을 durable 하게 저장해 크래시 후에도 동일 응답을 재생하도록 개선.
- **멱등 Redis 호출은 서킷브레이커 미적용**: 재고 경로(`redisStock`)와 달리 멱등 Redis 호출은 서킷브레이킹되지 않는다. 다만 `application.yml`의 command/connect timeout(각 500ms)으로 **bounded fail-fast** 되므로 무한 블로킹은 없다. → 필요 시 멱등 경로에도 서킷브레이커 추가 검토.
- **StockKeyInitializer 매 기동 재시드**: 애플리케이션이 시작될 때마다 Redis 재고 키를 재시드한다. 따라서 프로덕션에서는 **세일 오픈 직전 1회만** 재고를 워밍업해야 하며, 운영 중 재시작 때마다 자동 재시드되지 않도록(또는 의도된 값으로만 시드되도록) 가드해야 한다. → 환경 플래그/조건부 시드로 분리.

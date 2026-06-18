# 시스템 아키텍처

midnight-deal 플랫폼의 컴포넌트 구성, 핵심 흐름, 장애 시나리오를 기술한다.

---

## 1. 컴포넌트 다이어그램

```mermaid
graph TD
  Client["클라이언트"]

  subgraph Docker Compose 네트워크
    Nginx["Nginx 1.27<br/>:8080 라운드로빈"]

    subgraph "앱 계층 (2인스턴스)"
      App1["app1<br/>Spring Boot 3.5"]
      App2["app2<br/>Spring Boot 3.5"]
    end

    Redis["Redis 7<br/>재고 카운터<br/>멱등 마크·스냅샷<br/>구매자 SET"]
    MySQL["MySQL 8<br/>product / stock<br/>booking_order / payment<br/>purchase_lock / point_history<br/>idempotency"]

    subgraph "결제 게이트웨이 (Mock)"
      PGCard["CreditCard PG Mock"]
      PGYPay["Y_PAY PG Mock"]
    end
  end

  Client -->|"POST /api/bookings\nGET /api/checkout"| Nginx
  Nginx -->|"round-robin"| App1
  Nginx -->|"round-robin"| App2
  App1 <-->|"Lua reserve / release\nSETNX 멱등 마크"| Redis
  App2 <-->|"Lua reserve / release\nSETNX 멱등 마크"| Redis
  App1 <-->|"JPA / Flyway"| MySQL
  App2 <-->|"JPA / Flyway"| MySQL
  App1 -->|"charge / cancel"| PGCard
  App1 -->|"charge / cancel"| PGYPay
  App2 -->|"charge / cancel"| PGCard
  App2 -->|"charge / cancel"| PGYPay
```

---

## 2. Booking 흐름 (정상 경로)

```mermaid
flowchart TD
  A([POST /api/bookings]) --> B{멱등 마크\nSETNX idem:mark}
  B -- 실패(이미 존재) --> C{결과 스냅샷\nidem:res 존재?}
  C -- 있음 --> D([200 캐시 응답 반환])
  C -- 없음 --> E([409 DUPLICATE_REQUEST])
  B -- 성공(최초) --> F{Lua reserve\nstock / buyers}
  F -- SOLD_OUT --> G[DEL idem:mark] --> H([409 SOLD_OUT])
  F -- ALREADY_PURCHASED --> I[DEL idem:mark] --> J([409 ALREADY_PURCHASED])
  F -- RESERVED --> K[BookingConfirmService.confirm]
  K --> L[booking_order INSERT]
  L --> M[PaymentOrchestrator.pay]
  M --> N{결제 성공?}
  N -- 실패 --> O[부분 성공분 보상\n포인트 환불 / PG 취소]
  O --> P[Lua release\nSREM + INCR]
  P --> Q[DEL idem:mark] --> R([402 PAYMENT_FAILED])
  N -- 성공 --> S[stock.confirmSold\nUPDATE WHERE sold_qty<total_qty]
  S --> T{영향행 > 0?}
  T -- 0 --> U[트랜잭션 롤백] --> V([409 SOLD_OUT])
  T -- 1 --> W[purchase_lock INSERT\norder.markPaid]
  W --> X[SET idem:res 스냅샷] --> Y([200 PAID])
```

---

## 3. Checkout 흐름

```mermaid
flowchart TD
  A([GET /api/checkout?productId=1&userId=100]) --> B[ProductRepository.findById]
  B --> C[UserPointRepository.findByUserId]
  C --> D[PurchaseLockRepository.existsByUserIdAndProductId]
  D --> E([200 CheckoutResponse\nproductName / price / checkinAt / checkoutAt\navailablePoint / allowedCombinations / alreadyPurchased])
```

---

## 4. Redis 장애 → DB Degrade 흐름

```mermaid
flowchart TD
  A[StockReservationService.reserve] --> B{Redis 호출}
  B -- 정상 --> C[Lua reserve.lua 실행]
  C --> D([ReserveResult 반환])
  B -- 예외 / 서킷 OPEN --> E[Resilience4j\n@CircuitBreaker fallbackMethod]
  E --> F[DbStockFallback.reserve]
  F --> G[SELECT * FROM stock\nWHERE product_id=? FOR UPDATE]
  G --> H{lockRepo.exists\nByUserIdAndProductId?}
  H -- true --> I([ALREADY_PURCHASED])
  H -- false --> J{sold_qty >= total_qty?}
  J -- true --> K([SOLD_OUT])
  J -- false --> L([RESERVED — DB 비관락 직렬화])
  L --> M[이후 동일: confirmSold / purchase_lock]
```

서킷브레이커 설정 (`application.yml`):

| 파라미터 | 값 | 의미 |
|----------|----|------|
| `sliding-window-size` | 20 | 최근 20회 호출 기준 |
| `failure-rate-threshold` | 50% | 실패율 50% 초과 시 OPEN |
| `wait-duration-in-open-state` | 5s | OPEN 유지 시간 |
| `permitted-number-of-calls-in-half-open-state` | 3 | HALF-OPEN 탐침 횟수 |

---

## 5. 계층 구조 요약

```
BookingController
  └─ BookingService              ← 멱등·선점·보상 오케스트레이션 (트랜잭션 밖)
       ├─ IdempotencyService     ← Redis SETNX 마크 / 스냅샷
       ├─ StockReservationService← Lua reserve/release + CircuitBreaker
       │    └─ DbStockFallback   ← DB FOR UPDATE fallback
       └─ BookingConfirmService  ← @Transactional: 결제+영속+backstop
            ├─ PaymentOrchestrator ← Strategy dispatch + 보상
            │    ├─ PaymentCombinationPolicy ← 조합 규칙
            │    ├─ CreditCardProcessor / YPayProcessor / YPointProcessor
            │    └─ PointService  ← 포인트 차감·환불
            ├─ StockRepository.confirmSold  ← 조건부 UPDATE backstop
            └─ PurchaseLockRepository       ← PK backstop

CheckoutController
  └─ CheckoutService             ← 상품·포인트·구매이력 조회
```

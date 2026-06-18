# 상품 오픈 게이트 + 임의 오픈(관리자) 설계

> 날짜: 2026-06-18
> 목적: booking은 상품이 "오픈"된 경우에만 허용한다. 오픈 시각(`openAt`) 기준으로 판정하고,
> k6 부하 테스트 전 관리자 엔드포인트로 상품을 임의 오픈할 수 있게 한다.

## 배경

기존 코드에는 "상품이 오픈되어야 booking 가능"이라는 게이트가 실제로 구현돼 있지 않았다.
`Product.status`는 시드에서 `'OPEN'`으로 고정될 뿐 booking 흐름에서 한 번도 읽히지 않았고,
오픈 시각 개념도 없었다. 사실상 재고만 있으면 언제든 예약 가능한 상태였다.

## 게이트 판정 규칙 (booking 진입 시, idempotency 소비 전)

핫패스(최대 1000 TPS)는 Redis 중심이므로 게이트도 Redis로 판정한다. DB SELECT를 핫패스에
넣으면 k6 부하 측정이 왜곡된다.

```
val = GET open:{productId}
 - null 또는 "CLOSED"  → NOT_OPEN (409)   # 미스케줄 / status=CLOSED 백스톱
 - now < openEpoch     → NOT_OPEN (409)   # 오픈 시각 이전
 - else                → 통과
```

- DB(`product.open_at`, `product.status`)가 진실의 원천.
- Redis `open:{pid}`는 핫패스용 캐시 (기존 `stock:{pid}` 패턴과 동일).
- Redis 장애 시: ProductGate는 DB로 degrade(읽기)하여 판정 — 기존 Redis 장애 fallback 철학과 일치.

## 변경 컴포넌트

| # | 파일 | 변경 |
|---|------|------|
| 1 | `db/migration/V3__add_open_at.sql` (신규) | `product.open_at DATETIME NULL` 추가 + 기존 행을 닫힘(`2099-01-01`)으로 |
| 2 | `product/Product.java` | `openAt` 필드 + `markOpen()` (openAt=now, status=OPEN) 도메인 메서드 |
| 3 | `common/ErrorCode.java` | `NOT_OPEN(409)` 추가 |
| 4 | `stock/ProductGate.java` (신규) | Redis `open:{pid}` 판정. `ensureOpen()`/`publishOpen()`/`publishClosed()` + Redis 장애 시 DB fallback |
| 5 | `booking/BookingService.java` | `book()` 맨 앞에 `productGate.ensureOpen(productId)` |
| 6 | `stock/StockKeyInitializer.java` | warmUp 시 DB값 기준으로 `open:{pid}`도 함께 발행 |
| 7 | `product/ProductOpenService.java` (신규) | `@Transactional` DB `markOpen` + Redis `publishOpen` |
| 8 | `product/AdminProductController.java` (신규) | `POST /admin/products/{id}/open` |
| 9 | `k6/booking-load.js` | `setup()`에서 `POST /admin/products/1/open` 호출해 자동 오픈 |

## 임의 오픈 흐름

1. 앱 기동 → 시드 상품은 닫힘(open_at=2099) → warmUp이 `open:1=CLOSED`(미래) 발행.
2. k6 `setup()` 또는 `curl -X POST localhost:8080/admin/products/1/open`.
3. DB `open_at=now, status=OPEN` + Redis `open:1=<now epoch>` 발행 → 즉시 오픈.
4. booking 부하 시작.

## 테스트

- `ProductGateDecisionTest` (순수 단위): `isOpen(val, now)` — null/CLOSED/미래/과거/깨진값 5케이스.
- `ProductGateTest` (통합): 닫힘 → `ensureOpen` 시 `NOT_OPEN`; 관리자 오픈 후 통과 + DB open_at/status 검증.
- 기존 booking 통합 테스트: 베이스 클래스(`AbstractIntegrationTest`)에서 상품 1을 기본 오픈으로
  발행해 그린 유지(프로덕션 닫힘-기본은 마이그레이션+warmUp이 보장).

## 결정 사항

- **게이트를 idempotency 소비 전에 배치**: 닫힌 상품 요청이 멱등 키·재고를 건드리지 않고 부작용 0으로 거절.
- **시드를 닫힌 상태로**: "임의 오픈 후 테스트" 요구를 강제, 미오픈 실수 방지.
- **status는 백스톱**: 오픈 시각이 지나도 `CLOSED`면 강제 차단.

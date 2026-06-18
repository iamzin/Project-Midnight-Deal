#!/usr/bin/env bash
# 부하 테스트 후 상태 초기화 스크립트 (앱 재기동 없이 빠른 초기화)
#
# 초기화 대상:
#   - MySQL : 주문/결제/포인트이력/구매락 삭제, 재고 sold_qty=0, 포인트 잔액 복원
#   - Redis : FLUSHALL (재고 카운터/구매자 집합/멱등성 키 전부 제거)
#   - 앱     : 재시작 → StockKeyInitializer 가 DB 기준으로 Redis 재고 키 재적재(stock:*=10)
#
# 사용법: ./scripts/reset.sh   (docker compose 스택이 떠 있는 상태에서 실행)
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[reset] 1/3 MySQL 시드 상태로 초기화..."
docker compose exec -T -e MYSQL_PWD=deal mysql mysql -udeal deal -e "
DELETE FROM payment_detail;
DELETE FROM payment;
DELETE FROM point_history;
DELETE FROM purchase_lock;
DELETE FROM booking_order;
UPDATE stock SET sold_qty = 0, version = 0;
UPDATE user_point SET balance = 50000, version = 0;"

echo "[reset] 2/3 Redis FLUSHALL..."
docker compose exec -T redis redis-cli FLUSHALL >/dev/null

echo "[reset] 3/3 앱 재시작(재고 재적재)..."
docker compose restart app1 app2 >/dev/null

printf "[reset] 헬스 대기"
for i in $(seq 1 30); do
  if curl -s localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo " UP"
    break
  fi
  printf "."
  sleep 2
done

stock=$(docker compose exec -T redis redis-cli GET stock:1 2>/dev/null || echo "?")
echo "[reset] 완료 — redis stock:1 = ${stock} (재고 복원됨). 부하 테스트 재실행 가능."

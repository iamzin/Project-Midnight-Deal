# 부하 테스트 (k6)

평시 50 TPS + 00시 버스트 500~1000 TPS 재현.

## 실행
1. `docker compose up -d --build` (앱 2대 + Nginx + MySQL + Redis)
2. 재고 리셋이 필요하면 앱 재기동(시드 재적재) 또는 Redis 키 재설정
3. `k6 run k6/booking-load.js` (대상 변경: `BASE=http://localhost:8080 k6 run k6/booking-load.js`)

## 판정
- 5xx 비율 0 근접(매진 후 4xx는 정상 fast-fail)
- p95 지연·에러율을 결과로 첨부

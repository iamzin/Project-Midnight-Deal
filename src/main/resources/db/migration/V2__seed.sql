INSERT INTO product (id, name, price, checkin_at, checkout_at, total_stock, status)
VALUES (1, 'Midnight Deal Room', 100000, '2026-07-01 15:00:00', '2026-07-02 11:00:00', 10, 'OPEN');

INSERT INTO stock (product_id, total_qty, sold_qty, version) VALUES (1, 10, 0, 0);

-- MySQL 기본 cte_max_recursion_depth=1000 이므로 2000행 삽입을 위해 세션 값 상향
SET SESSION cte_max_recursion_depth = 10000;

-- 테스트용 사용자 포인트 (user 1~2000)
INSERT INTO user_point (user_id, balance, version)
SELECT seq, 50000, 0 FROM (
  WITH RECURSIVE s(seq) AS (SELECT 1 UNION ALL SELECT seq+1 FROM s WHERE seq < 2000)
  SELECT seq FROM s
) t;

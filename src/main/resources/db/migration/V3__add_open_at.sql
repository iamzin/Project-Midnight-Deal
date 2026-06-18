-- 상품 오픈 시각. booking 게이트 판정의 진실의 원천.
ALTER TABLE product ADD COLUMN open_at DATETIME NULL;

-- 기존 상품은 닫힘 상태로(임의 오픈 전까지 차단): 먼 미래로 설정.
-- k6 테스트 전 POST /admin/products/{id}/open 으로 오픈해야 booking 가능.
UPDATE product SET open_at = '2099-01-01 00:00:00';

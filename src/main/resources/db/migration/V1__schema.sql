CREATE TABLE product (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  price BIGINT NOT NULL,
  checkin_at DATETIME NOT NULL,
  checkout_at DATETIME NOT NULL,
  total_stock INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
);

CREATE TABLE stock (
  product_id BIGINT PRIMARY KEY,
  total_qty INT NOT NULL,
  sold_qty INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES product(id),
  CONSTRAINT chk_sold CHECK (sold_qty <= total_qty)
);

CREATE TABLE user_point (
  user_id BIGINT PRIMARY KEY,
  balance BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE booking_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL,
  total_amount BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  CONSTRAINT uq_idem UNIQUE (idempotency_key)
);

CREATE TABLE purchase_lock (
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (user_id, product_id)
);

CREATE TABLE payment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  total_amount BIGINT NOT NULL,
  requested_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  CONSTRAINT fk_pay_order FOREIGN KEY (order_id) REFERENCES booking_order(id)
);

CREATE TABLE payment_detail (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id BIGINT NOT NULL,
  method VARCHAR(20) NOT NULL,
  amount BIGINT NOT NULL,
  pg_tx_id VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL,
  CONSTRAINT fk_detail_pay FOREIGN KEY (payment_id) REFERENCES payment(id)
);

CREATE TABLE point_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  order_id BIGINT NULL,
  amount BIGINT NOT NULL,
  type VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL
);

CREATE TABLE idempotency (
  idempotency_key VARCHAR(100) PRIMARY KEY,
  order_id BIGINT NULL,
  response_snapshot TEXT NULL,
  created_at DATETIME NOT NULL
);

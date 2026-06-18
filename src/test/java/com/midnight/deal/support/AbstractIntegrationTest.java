package com.midnight.deal.support;

import com.midnight.deal.stock.ProductGate;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("deal");
    static final RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7"));

    static {
        mysql.start();
        redis.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    protected ProductGate productGate;

    @BeforeEach
    void resetSharedState() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM payment_detail");
            jdbc.update("DELETE FROM payment");
            jdbc.update("DELETE FROM point_history");
            jdbc.update("DELETE FROM purchase_lock");
            jdbc.update("DELETE FROM booking_order");
            jdbc.update("UPDATE stock SET sold_qty = 0, version = 0");
            jdbc.update("UPDATE user_point SET balance = 50000, version = 0");
            // 통합 테스트 기본: 상품 1을 오픈 상태로 (게이트 테스트는 개별적으로 override)
            jdbc.update("UPDATE product SET open_at = '2000-01-01 00:00:00', status = 'OPEN'");
        });
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        productGate.publishOpen(1L, LocalDateTime.now().minusMinutes(1));
    }
}

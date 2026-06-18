package com.midnight.deal.stock;

import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class RedisFallbackTest extends AbstractIntegrationTest {
    @Autowired DbStockFallback fallback;

    @Test
    void db_path_reserves_within_stock() {
        // Redis 없이도 DB 비관락 경로가 정합성 유지하며 선점 판정.
        // 결정성을 위해 다른 테스트(BookingApiTest 의 user 200/201/202/300/301)와
        // 겹치지 않는 fresh user 를 사용한다. Testcontainers MySQL 은 스위트 전역
        // 싱글톤이라 purchase_lock 이 클래스 간에 잔류하기 때문이다.
        ReserveResult r = fallback.reserve(1L, 1300L);
        assertThat(r).isEqualTo(ReserveResult.RESERVED);
    }
}

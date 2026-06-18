package com.midnight.deal.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Redis 연결 장애 시 IdempotencyService degrade 동작 검증.
 * Spring Context / Testcontainers 없는 순수 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceDegradeTest {

    @Mock
    private StringRedisTemplate mockRedis;

    @Mock
    private ValueOperations<String, String> mockOps;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        when(mockRedis.opsForValue()).thenReturn(mockOps);
        service = new IdempotencyService(mockRedis);
    }

    @Test
    void tryBegin_redis_down_returns_true() {
        when(mockOps.setIfAbsent(anyString(), anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        boolean result = service.tryBegin("key-1");

        assertThat(result).isTrue();
    }

    @Test
    void findResult_redis_down_returns_empty() {
        when(mockOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        Optional<String> result = service.findResult("key-2");

        assertThat(result).isEmpty();
    }
}

package com.midnight.deal.stock;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

@Service @RequiredArgsConstructor
public class StockReservationService {
    private final StringRedisTemplate redis;
    private final RedisScript<Long> reserveScript;
    private final RedisScript<Long> releaseScript;
    private final DbStockFallback dbFallback;

    private String stockKey(long pid){ return "stock:" + pid; }
    private String buyersKey(long pid){ return "buyers:" + pid; }

    public void initStock(long productId, int qty) {
        redis.opsForValue().set(stockKey(productId), String.valueOf(qty));
        redis.delete(buyersKey(productId));
    }

    @CircuitBreaker(name = "redisStock", fallbackMethod = "reserveViaDb")
    public ReserveResult reserve(long productId, long userId) {
        Long r = redis.execute(reserveScript,
            List.of(stockKey(productId), buyersKey(productId)), String.valueOf(userId));
        long code = r == null ? 0 : r;
        if (code == 1) return ReserveResult.RESERVED;
        if (code == -1) return ReserveResult.ALREADY_PURCHASED;
        return ReserveResult.SOLD_OUT;
    }

    // 서킷 OPEN 또는 Redis 예외 시 호출 (시그니처 = 원본 + Throwable)
    public ReserveResult reserveViaDb(long productId, long userId, Throwable t) {
        return dbFallback.reserve(productId, userId);
    }

    public void release(long productId, long userId) {
        redis.execute(releaseScript,
            List.of(stockKey(productId), buyersKey(productId)), String.valueOf(userId));
    }
}

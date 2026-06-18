package com.midnight.deal.booking;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Optional;

@Service @RequiredArgsConstructor
public class IdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private final StringRedisTemplate redis;
    private static final Duration TTL = Duration.ofMinutes(10);

    private String mark(String key){ return "idem:mark:" + key; }
    private String result(String key){ return "idem:res:" + key; }

    /** 최초 진입이면 true. 이미 처리중/완료면 false.
     *  Redis 장애 시 true 반환(degrade) — DB UNIQUE 제약이 중복을 막는다. */
    public boolean tryBegin(String key) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(mark(key), "1", TTL);
            return Boolean.TRUE.equals(ok);
        } catch (DataAccessException e) {
            log.warn("[Idempotency] Redis 장애 — tryBegin degrade (key={}): {}", key, e.getMessage());
            return true;
        }
    }

    public void saveResult(String key, String json) {
        try {
            redis.opsForValue().set(result(key), json, TTL);
        } catch (DataAccessException e) {
            log.warn("[Idempotency] Redis 장애 — saveResult skipped (key={}): {}", key, e.getMessage());
        }
    }

    public Optional<String> findResult(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(result(key)));
        } catch (DataAccessException e) {
            log.warn("[Idempotency] Redis 장애 — findResult degrade empty (key={}): {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 처리 실패 시 마크 해제 → 동일 키 재시도 허용. */
    public void clear(String key) {
        try {
            redis.delete(mark(key));
            redis.delete(result(key));
        } catch (DataAccessException e) {
            // best-effort; Redis 장애 시 무시
        }
    }
}

package com.midnight.deal.stock;

import com.midnight.deal.common.BusinessException;
import com.midnight.deal.common.ErrorCode;
import com.midnight.deal.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 상품 오픈 게이트. booking 진입 시 "오픈 시각이 지났는가"를 핫패스에서 판정한다.
 *
 * 진실의 원천은 DB(product.open_at, product.status)이고, Redis open:{pid}는 핫패스 캐시다.
 * (기존 stock:{pid} 패턴과 동일.) Redis 장애 시 DB로 degrade하여 판정한다.
 */
@Service
@RequiredArgsConstructor
public class ProductGate {

    static final String CLOSED = "CLOSED";

    private final StringRedisTemplate redis;
    private final ProductRepository productRepo;

    static String openKey(long pid) { return "open:" + pid; }

    /** 닫혀 있으면 NOT_OPEN 예외. 부작용(멱등 마크/재고 선점) 발생 전에 호출되어야 한다. */
    public void ensureOpen(long productId) {
        boolean open;
        try {
            open = isOpen(redis.opsForValue().get(openKey(productId)), System.currentTimeMillis());
        } catch (RuntimeException redisDown) {
            open = isOpenInDb(productId); // Redis 장애 → DB로 degrade
        }
        if (!open) throw new BusinessException(ErrorCode.NOT_OPEN);
    }

    /** 오픈 시각을 Redis에 발행. */
    public void publishOpen(long productId, LocalDateTime openAt) {
        redis.opsForValue().set(openKey(productId), String.valueOf(toEpochMillis(openAt)));
    }

    /** 강제 닫힘 마커 발행. */
    public void publishClosed(long productId) {
        redis.opsForValue().set(openKey(productId), CLOSED);
    }

    /** 순수 판정: null/CLOSED/미래 epoch/깨진 값 → 닫힘, now 이상의 epoch → 오픈. */
    static boolean isOpen(String val, long nowMillis) {
        if (val == null || CLOSED.equals(val)) return false;
        try {
            return nowMillis >= Long.parseLong(val);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isOpenInDb(long productId) {
        return productRepo.findById(productId)
            .map(p -> !CLOSED.equals(p.getStatus())
                && p.getOpenAt() != null
                && !LocalDateTime.now().isBefore(p.getOpenAt()))
            .orElse(false);
    }

    static long toEpochMillis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

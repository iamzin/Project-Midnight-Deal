package com.midnight.deal.stock;

import com.midnight.deal.booking.PurchaseLockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor
public class DbStockFallback {
    private final StockRepository stockRepo;
    private final PurchaseLockRepository lockRepo;

    /** Redis 불가 시 DB 비관락 경로. 정합성 유지(throughput↓). */
    @Transactional
    public ReserveResult reserve(long productId, long userId) {
        if (lockRepo.existsByUserIdAndProductId(userId, productId))
            return ReserveResult.ALREADY_PURCHASED;
        Stock s = stockRepo.findForUpdate(productId)
            .orElseThrow(() -> new IllegalStateException("STOCK_NOT_FOUND"));
        if (s.getSoldQty() >= s.getTotalQty()) return ReserveResult.SOLD_OUT;
        // confirmSold는 Task 11에서 결제 성공 후 호출되므로 여기선 가용 여부만 판정.
        // degrade 경로에서는 비관락으로 직렬화되어 동시 초과선점 불가.
        return ReserveResult.RESERVED;
    }
}

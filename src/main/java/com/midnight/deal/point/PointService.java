package com.midnight.deal.point;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PointService {
    private final UserPointRepository pointRepo;
    private final PointHistoryRepository historyRepo;

    @Transactional
    public void use(long userId, long orderId, long amount) {
        UserPoint p = pointRepo.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("POINT_ACCOUNT_NOT_FOUND"));
        p.use(amount); // 잔액 부족 시 INSUFFICIENT_POINT
        historyRepo.save(new PointHistory(userId, orderId, -amount, "USE"));
    }

    @Transactional
    public void refund(long userId, long orderId, long amount) {
        UserPoint p = pointRepo.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("POINT_ACCOUNT_NOT_FOUND"));
        p.refund(amount);
        historyRepo.save(new PointHistory(userId, orderId, amount, "REFUND"));
    }
}

package com.midnight.deal.stock;

import com.midnight.deal.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class StockKeyInitializer {
    private final ProductRepository productRepo;
    private final StockRepository stockRepo;
    private final StockReservationService reservation;
    private final ProductGate productGate;

    // 앱 2대가 동시에 호출해도 set은 동일 값 → 멱등. 운영에선 오픈 직전 1회 적재가 이상적.
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        productRepo.findAll().forEach(p -> {
            int remaining = stockRepo.findById(p.getId())
                .map(s -> s.getTotalQty() - s.getSoldQty()).orElse(0);
            reservation.initStock(p.getId(), remaining);

            // 오픈 키도 DB값 기준으로 발행. CLOSED 이거나 open_at 미설정이면 닫힘 마커.
            if ("CLOSED".equals(p.getStatus()) || p.getOpenAt() == null) {
                productGate.publishClosed(p.getId());
            } else {
                productGate.publishOpen(p.getId(), p.getOpenAt());
            }
        });
    }
}

package com.midnight.deal.booking;

import jakarta.persistence.*;
import lombok.Getter; import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity @Table(name = "booking_order") @Getter @NoArgsConstructor
public class BookingOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long productId;
    private Long userId;
    private String idempotencyKey;
    @Enumerated(EnumType.STRING) private OrderStatus status;
    private long totalAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public BookingOrder(Long productId, Long userId, String key, long totalAmount) {
        this.productId = productId; this.userId = userId; this.idempotencyKey = key;
        this.totalAmount = totalAmount; this.status = OrderStatus.PENDING;
        this.createdAt = LocalDateTime.now(); this.updatedAt = this.createdAt;
    }
    public void markPaid() { this.status = OrderStatus.PAID; this.updatedAt = LocalDateTime.now(); }
    public void markFailed() { this.status = OrderStatus.FAILED; this.updatedAt = LocalDateTime.now(); }
}

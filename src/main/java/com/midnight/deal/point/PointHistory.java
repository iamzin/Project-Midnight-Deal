package com.midnight.deal.point;

import jakarta.persistence.*;
import lombok.Getter; import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity @Table(name = "point_history") @Getter @NoArgsConstructor
public class PointHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long userId;
    private Long orderId;
    private long amount;
    private String type; // USE / REFUND
    private LocalDateTime createdAt;

    public PointHistory(Long userId, Long orderId, long amount, String type) {
        this.userId = userId; this.orderId = orderId; this.amount = amount;
        this.type = type; this.createdAt = LocalDateTime.now();
    }
}

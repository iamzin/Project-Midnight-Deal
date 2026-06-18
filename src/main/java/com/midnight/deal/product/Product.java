package com.midnight.deal.product;

import jakarta.persistence.*;
import lombok.Getter;
import java.time.LocalDateTime;

@Entity @Table(name = "product") @Getter
public class Product {
    @Id private Long id;
    private String name;
    private long price;
    private LocalDateTime checkinAt;
    private LocalDateTime checkoutAt;
    private int totalStock;
    private String status;
    private LocalDateTime openAt;

    /** 임의 오픈: 오픈 시각을 현재로 당기고 상태를 OPEN으로. (트랜잭션 내 dirty checking으로 영속) */
    public void markOpen() {
        this.openAt = LocalDateTime.now();
        this.status = "OPEN";
    }
}

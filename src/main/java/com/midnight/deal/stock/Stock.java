package com.midnight.deal.stock;

import jakarta.persistence.*;
import lombok.Getter;

@Entity @Table(name = "stock") @Getter
public class Stock {
    @Id @Column(name = "product_id") private Long productId;
    private int totalQty;
    private int soldQty;
    @Version private long version;
}

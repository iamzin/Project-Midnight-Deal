package com.midnight.deal.point;

import jakarta.persistence.*;
import lombok.Getter;

@Entity @Table(name = "user_point") @Getter
public class UserPoint {
    @Id @Column(name = "user_id") private Long userId;
    private long balance;
    @Version private long version;

    public void use(long amount) {
        if (balance < amount) throw new IllegalStateException("INSUFFICIENT_POINT");
        this.balance -= amount;
    }
    public void refund(long amount) { this.balance += amount; }
}

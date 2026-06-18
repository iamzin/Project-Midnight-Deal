package com.midnight.deal.booking;

import jakarta.persistence.*;
import lombok.Getter; import lombok.NoArgsConstructor;
import java.io.Serializable; import java.time.LocalDateTime; import java.util.Objects;

@Entity @Table(name = "purchase_lock") @Getter @NoArgsConstructor
@IdClass(PurchaseLock.Pk.class)
public class PurchaseLock {
    @Id private Long userId;
    @Id private Long productId;
    private Long orderId;
    private LocalDateTime createdAt;

    public PurchaseLock(Long userId, Long productId, Long orderId) {
        this.userId = userId; this.productId = productId; this.orderId = orderId;
        this.createdAt = LocalDateTime.now();
    }
    public static class Pk implements Serializable {
        private Long userId; private Long productId;
        public Pk() {} public Pk(Long u, Long p){userId=u;productId=p;}
        @Override public boolean equals(Object o){ if(!(o instanceof Pk k))return false;
            return Objects.equals(userId,k.userId)&&Objects.equals(productId,k.productId);}
        @Override public int hashCode(){return Objects.hash(userId,productId);}
    }
}

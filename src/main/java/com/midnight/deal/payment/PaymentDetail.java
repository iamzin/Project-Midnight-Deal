package com.midnight.deal.payment;

import jakarta.persistence.*;
import lombok.Getter; import lombok.NoArgsConstructor;

@Entity @Table(name = "payment_detail") @Getter @NoArgsConstructor
public class PaymentDetail {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) private PaymentMethod method;
    private long amount;
    private String pgTxId;
    private String status;

    public PaymentDetail(PaymentMethod method, long amount, String pgTxId, String status) {
        this.method = method; this.amount = amount; this.pgTxId = pgTxId; this.status = status;
    }
}

package com.midnight.deal.payment;

import jakarta.persistence.*;
import lombok.Getter; import lombok.NoArgsConstructor;
import java.time.LocalDateTime; import java.util.ArrayList; import java.util.List;

@Entity @Table(name = "payment") @Getter @NoArgsConstructor
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long orderId;
    private String status;
    private long totalAmount;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;

    // nullable=false → Hibernate가 자식 INSERT에 payment_id를 포함(INSERT 후 별도 UPDATE 제거).
    // payment_detail.payment_id 는 NOT NULL 이므로 이 설정이 없으면 초기 INSERT가 제약 위반.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "payment_id", nullable = false)
    private List<PaymentDetail> details = new ArrayList<>();

    public Payment(Long orderId, long totalAmount) {
        this.orderId = orderId; this.totalAmount = totalAmount;
        this.status = "REQUESTED"; this.requestedAt = LocalDateTime.now();
    }
    public void addDetail(PaymentDetail d){ details.add(d); }
    public void complete(){ this.status="COMPLETED"; this.completedAt=LocalDateTime.now(); }
    public void fail(){ this.status="FAILED"; this.completedAt=LocalDateTime.now(); }
}

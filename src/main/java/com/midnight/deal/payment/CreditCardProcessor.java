package com.midnight.deal.payment;

import com.midnight.deal.payment.gateway.PaymentGateway;
import com.midnight.deal.payment.gateway.PgResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CreditCardProcessor implements PaymentProcessor {
    private final PaymentGateway gateway;
    public boolean supports(PaymentMethod m){ return m == PaymentMethod.CREDIT_CARD; }
    public PaymentResult process(PaymentCommand c){
        PgResult r = gateway.charge(PaymentMethod.CREDIT_CARD, c.userId(), c.amount());
        return new PaymentResult(r.success(), PaymentMethod.CREDIT_CARD, c.amount(), r.txId(), r.failureReason());
    }
    public void cancel(PaymentResult r){ if (r.pgTxId()!=null) gateway.cancel(r.pgTxId()); }
}

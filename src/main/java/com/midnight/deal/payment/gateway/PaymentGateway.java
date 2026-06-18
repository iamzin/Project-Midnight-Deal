package com.midnight.deal.payment.gateway;

import com.midnight.deal.payment.PaymentMethod;

public interface PaymentGateway {
    PgResult charge(PaymentMethod method, long userId, long amount);

    void cancel(String txId);
}

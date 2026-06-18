package com.midnight.deal.payment;
public interface PaymentProcessor {
    boolean supports(PaymentMethod method);
    PaymentResult process(PaymentCommand cmd);
    void cancel(PaymentResult result);   // 보상
}

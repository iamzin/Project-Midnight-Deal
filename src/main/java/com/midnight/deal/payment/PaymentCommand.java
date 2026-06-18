package com.midnight.deal.payment;
public record PaymentCommand(PaymentMethod method, long userId, long orderId, long amount) {}

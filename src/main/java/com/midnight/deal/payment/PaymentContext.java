package com.midnight.deal.payment;
import java.util.List;
public record PaymentContext(long orderId, long userId, long totalAmount, List<PaymentLine> lines) {}

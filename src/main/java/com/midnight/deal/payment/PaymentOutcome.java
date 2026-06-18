package com.midnight.deal.payment;
import java.util.List;
public record PaymentOutcome(boolean success, String failureReason, List<PaymentResult> results) {}

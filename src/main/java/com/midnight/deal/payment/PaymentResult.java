package com.midnight.deal.payment;
public record PaymentResult(boolean success, PaymentMethod method, long amount,
                            String pgTxId, String failureReason) {}

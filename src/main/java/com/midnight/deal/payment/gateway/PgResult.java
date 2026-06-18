package com.midnight.deal.payment.gateway;

public record PgResult(boolean success, String txId, String failureReason) {
    public static PgResult ok(String txId) {
        return new PgResult(true, txId, null);
    }

    public static PgResult fail(String reason) {
        return new PgResult(false, null, reason);
    }
}

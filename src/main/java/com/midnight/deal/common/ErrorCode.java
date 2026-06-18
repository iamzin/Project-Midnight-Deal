package com.midnight.deal.common;

public enum ErrorCode {
    SOLD_OUT(409), ALREADY_PURCHASED(409), DUPLICATE_REQUEST(409),
    NOT_OPEN(409), PAYMENT_FAILED(402), INVALID_COMBINATION(400), AMOUNT_MISMATCH(400),
    PRODUCT_NOT_FOUND(404), SERVICE_UNAVAILABLE(503);
    public final int httpStatus;
    ErrorCode(int s){ this.httpStatus = s; }
}

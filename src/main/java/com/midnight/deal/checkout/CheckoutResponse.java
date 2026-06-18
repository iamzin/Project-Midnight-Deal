package com.midnight.deal.checkout;
import java.time.LocalDateTime; import java.util.List;

public record CheckoutResponse(
    String productName, long price,
    LocalDateTime checkinAt, LocalDateTime checkoutAt,
    long availablePoint, List<String> allowedCombinations, boolean alreadyPurchased
) {}

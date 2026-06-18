package com.midnight.deal.booking.dto;

import com.midnight.deal.payment.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record BookingRequest(
    @NotNull Long productId,
    @NotNull Long userId,
    @NotEmpty @Valid List<Line> payments,
    @Positive long totalAmount
) {
    public record Line(@NotNull PaymentMethod method, @Positive long amount) {}
}

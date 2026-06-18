package com.midnight.deal.booking;

import com.midnight.deal.booking.dto.BookingRequest;
import com.midnight.deal.booking.dto.BookingResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {
    private final BookingService service;

    @PostMapping
    public BookingResponse book(@RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody BookingRequest req) {
        return service.book(key, req);
    }
}

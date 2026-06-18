package com.midnight.deal.checkout;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/checkout") @RequiredArgsConstructor
public class CheckoutController {
    private final CheckoutService service;

    @GetMapping
    public CheckoutResponse checkout(@RequestParam Long productId, @RequestParam Long userId) {
        return service.checkout(productId, userId);
    }
}

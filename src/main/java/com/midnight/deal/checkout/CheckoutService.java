package com.midnight.deal.checkout;

import com.midnight.deal.booking.PurchaseLockRepository;
import com.midnight.deal.point.UserPointRepository;
import com.midnight.deal.product.Product; import com.midnight.deal.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service @RequiredArgsConstructor
public class CheckoutService {
    private final ProductRepository productRepo;
    private final UserPointRepository pointRepo;
    private final PurchaseLockRepository lockRepo;

    public CheckoutResponse checkout(Long productId, Long userId) {
        Product p = productRepo.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        long point = pointRepo.findByUserId(userId).map(up -> up.getBalance()).orElse(0L);
        boolean purchased = lockRepo.existsByUserIdAndProductId(userId, productId);
        return new CheckoutResponse(p.getName(), p.getPrice(), p.getCheckinAt(), p.getCheckoutAt(),
            point, List.of("CREDIT_CARD+Y_POINT", "Y_PAY+Y_POINT", "CREDIT_CARD", "Y_PAY", "Y_POINT"),
            purchased);
    }
}

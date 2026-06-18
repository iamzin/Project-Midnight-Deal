package com.midnight.deal.product;

import com.midnight.deal.common.BusinessException;
import com.midnight.deal.common.ErrorCode;
import com.midnight.deal.stock.ProductGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 임의 오픈: DB를 진실의 원천으로 갱신하고 Redis 핫패스 캐시를 발행한다. */
@Service
@RequiredArgsConstructor
public class ProductOpenService {

    private final ProductRepository productRepo;
    private final ProductGate productGate;

    @Transactional
    public Map<String, Object> open(long productId) {
        Product p = productRepo.findById(productId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        p.markOpen();                          // open_at=now, status=OPEN (dirty checking)
        productGate.publishOpen(productId, p.getOpenAt());
        return Map.of("productId", productId, "openAt", p.getOpenAt().toString(), "status", p.getStatus());
    }
}

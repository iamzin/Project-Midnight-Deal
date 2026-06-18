package com.midnight.deal.product;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 운영/테스트용 관리자 엔드포인트. k6 부하 전 상품을 임의 오픈한다. */
@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductOpenService service;

    @PostMapping("/{id}/open")
    public Map<String, Object> open(@PathVariable long id) {
        return service.open(id);
    }
}

package com.midnight.deal.domain;

import com.midnight.deal.stock.StockRepository;
import com.midnight.deal.product.ProductRepository;
import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryTest extends AbstractIntegrationTest {
    @Autowired ProductRepository productRepo;
    @Autowired StockRepository stockRepo;

    @Test
    void seedLoaded() {
        assertThat(productRepo.findById(1L)).isPresent();
    }

    @Test
    @Transactional
    void confirmSold_increments_until_total() {
        int affected = stockRepo.confirmSold(1L);
        assertThat(affected).isEqualTo(1); // sold_qty 0 -> 1
    }
}

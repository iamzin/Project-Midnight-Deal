package com.midnight.deal.booking;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PurchaseLockRepository extends JpaRepository<PurchaseLock, PurchaseLock.Pk> {
    boolean existsByUserIdAndProductId(Long userId, Long productId);
    void deleteByUserIdAndProductId(Long userId, Long productId);
}

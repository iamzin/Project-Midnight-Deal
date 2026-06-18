package com.midnight.deal.booking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface BookingOrderRepository extends JpaRepository<BookingOrder, Long> {
    Optional<BookingOrder> findByIdempotencyKey(String key);
}

package com.midnight.deal.point;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface UserPointRepository extends JpaRepository<UserPoint, Long> {
    Optional<UserPoint> findByUserId(Long userId);
}

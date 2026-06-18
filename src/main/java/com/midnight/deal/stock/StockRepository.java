package com.midnight.deal.stock;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    // clearAutomatically 사용 금지: 이 bulk UPDATE는 confirm() 트랜잭션에서 주문 INSERT '이후' 실행된다.
    // 컨텍스트를 clear 하면 방금 영속한 BookingOrder가 detach 되어, 이후 order.markPaid()가 반영되지
    // 않고 status가 PENDING으로 남는다. confirm() 흐름은 이 UPDATE 이후 Stock 엔티티를 읽지 않으므로
    // L1 캐시 무효화가 필요 없다.
    @Transactional
    @Modifying
    @Query("update Stock s set s.soldQty = s.soldQty + 1, s.version = s.version + 1 " +
           "where s.productId = :pid and s.soldQty < s.totalQty")
    int confirmSold(@Param("pid") Long pid);

    @Transactional
    @Modifying
    @Query("update Stock s set s.soldQty = s.soldQty - 1 where s.productId = :pid and s.soldQty > 0")
    int restore(@Param("pid") Long pid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productId = :pid")
    Optional<Stock> findForUpdate(@Param("pid") Long pid);
}

package com.midnight.deal.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midnight.deal.booking.dto.BookingRequest;
import com.midnight.deal.booking.dto.BookingResponse;
import com.midnight.deal.common.BusinessException;
import com.midnight.deal.common.ErrorCode;
import com.midnight.deal.stock.ProductGate;
import com.midnight.deal.stock.ReserveResult;
import com.midnight.deal.stock.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookingService {
    private final IdempotencyService idempotency;
    private final StockReservationService reservation;
    private final BookingConfirmService confirmService;
    private final ProductGate productGate;
    private final ObjectMapper om;

    /**
     * 멱등 → 선점 → confirm 위임 → 실패 시 보상. 트랜잭션 밖(Redis 보상은 트랜잭션 밖에서 수행).
     *
     * 보상 정책(핵심): release()는 "이 요청이 직접 선점(RESERVED)한 경우에만" 호출한다.
     * SOLD_OUT/ALREADY_PURCHASED는 이 요청이 선점하지 않았으므로 절대 release하면 안 된다.
     * 특히 ALREADY_PURCHASED 유저는 직전 성공 구매로 buyers set에 남아 있으므로,
     * release를 호출하면 직전 구매자가 SREM되고 재고가 INCR되어 데이터가 오염된다.
     */
    @SneakyThrows
    public BookingResponse book(String idemKey, BookingRequest req) {
        // 0) 오픈 게이트 — 닫힌 상품이면 멱등 마크/재고 선점을 건드리지 않고 즉시 거절(부작용 0)
        productGate.ensureOpen(req.productId());

        // 1) 멱등성
        if (!idempotency.tryBegin(idemKey)) {
            return idempotency.findResult(idemKey)
                .map(prev -> deserialize(prev))
                // 마크는 있으나 결과 미보존 = 다른 요청이 진행중 → 마크 해제하지 않는다(소유권 없음)
                .orElseThrow(() -> new BusinessException(ErrorCode.DUPLICATE_REQUEST, "IN_PROGRESS"));
        }

        // 여기서부터 이 요청이 멱등 마크의 소유자다.
        // 2) 재고 선점 + 1인 1구매 (Redis 원자)
        ReserveResult rr = reservation.reserve(req.productId(), req.userId());
        if (rr == ReserveResult.SOLD_OUT) {
            idempotency.clear(idemKey); // 선점 안 함 → release 금지, 마크만 해제(향후 재시도 허용)
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
        if (rr == ReserveResult.ALREADY_PURCHASED) {
            idempotency.clear(idemKey); // 선점 안 함 → release 금지(직전 구매자 보호), 마크만 해제
            throw new BusinessException(ErrorCode.ALREADY_PURCHASED);
        }

        // rr == RESERVED — 이 요청이 선점을 보유한다. 이후 실패 시에만 release로 보상한다.
        try {
            BookingResponse res = confirmService.confirm(idemKey, req);
            idempotency.saveResult(idemKey, om.writeValueAsString(res));
            return res;
        } catch (RuntimeException e) {
            reservation.release(req.productId(), req.userId()); // 이 요청의 선점만 원복
            idempotency.clear(idemKey);
            throw e;
        }
    }

    @SneakyThrows
    private BookingResponse deserialize(String json) {
        return om.readValue(json, BookingResponse.class);
    }
}

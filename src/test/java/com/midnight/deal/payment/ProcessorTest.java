package com.midnight.deal.payment;

import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ProcessorTest extends AbstractIntegrationTest {
    @Autowired List<PaymentProcessor> processors;

    @Test
    void each_method_has_one_processor() {
        for (PaymentMethod m : PaymentMethod.values()) {
            long n = processors.stream().filter(p -> p.supports(m)).count();
            assertThat(n).as("method %s", m).isEqualTo(1);
        }
    }
}

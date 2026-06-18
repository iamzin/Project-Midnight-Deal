package com.midnight.deal.checkout;

import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CheckoutApiTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void checkout_returns_product_and_point() throws Exception {
        mvc.perform(get("/api/checkout").param("productId","1").param("userId","1")
                .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.productName").value("Midnight Deal Room"))
           .andExpect(jsonPath("$.availablePoint").value(50000))
           .andExpect(jsonPath("$.alreadyPurchased").value(false));
    }
}

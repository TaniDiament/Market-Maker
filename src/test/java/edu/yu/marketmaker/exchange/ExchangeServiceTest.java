package edu.yu.marketmaker.exchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.yu.marketmaker.memory.Repository;
import edu.yu.marketmaker.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-layer tests for ExchangeService.
 *
 * Verifies:
 *   - GET  /health returns service info
 *   - GET  /quotes/{symbol} returns quote or 404
 *   - PUT  /quotes/{symbol} stores a quote
 *   - POST /orders delegates to OrderDispatcher
 *   - POST /orders returns 400 on validation failure
 *   - POST /orders returns 404 on missing quote
 */
@WebMvcTest(ExchangeService.class)
@ActiveProfiles("exchange")
class ExchangeServiceTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private Repository<String, Quote> quoteRepository;

    @MockitoBean
    private OrderDispatcher orderDispatcher;

    @MockitoBean
private ReservationRequester reservationRequester;

    // Health

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(true))
                .andExpect(jsonPath("$.name").value("Exchange Service"));
    }

    // GET /quotes/{symbol}

    @Test
    void getQuoteReturnsQuoteWhenExists() throws Exception {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        mockMvc.perform(get("/quotes/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.bidPrice").value(99.0))
                .andExpect(jsonPath("$.askPrice").value(101.0))
                .andExpect(jsonPath("$.bidQuantity").value(10))
                .andExpect(jsonPath("$.askQuantity").value(10));
    }

    @Test
    void getQuoteReturns404WhenMissing() throws Exception {
        when(quoteRepository.get("NOPE")).thenReturn(Optional.empty());

        mockMvc.perform(get("/quotes/NOPE"))
                .andExpect(status().isNotFound());
    }

    // PUT /quotes/{symbol}

    @Test
    void putQuoteStoresQuote() throws Exception {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);

        mockMvc.perform(put("/quotes/AAPL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quote)))
                .andExpect(status().isOk());

        verify(quoteRepository).put(any(Quote.class));
    }

    // POST /orders

    @Test
    void submitOrderDelegatesToDispatcher() throws Exception {
        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk());

        verify(orderDispatcher).dispatchOrder(any(ExternalOrder.class));
    }

    @Test
    void submitOrderReturns400WhenValidationFails() throws Exception {
        doThrow(new OrderValidationException("bad order"))
                .when(orderDispatcher).dispatchOrder(any());

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitOrderReturns404WhenQuoteMissing() throws Exception {
        doThrow(new QuoteNotFoundException("NOPE"))
                .when(orderDispatcher).dispatchOrder(any());

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "NOPE", 5, 101.0, Side.BUY);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isNotFound());
    }

    private Quote makeQuote(String symbol, double bid, int bidQty, double ask, int askQty) {
        return new Quote(symbol, bid, bidQty, ask, askQty, UUID.randomUUID(),
                System.currentTimeMillis() + 30_000);
    }
}
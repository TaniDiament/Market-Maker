package edu.yu.velocitytrading.exchange;

import edu.yu.velocitytrading.memory.Repository;
import edu.yu.velocitytrading.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FillOrderDispatcher
 *
 * From requirements.md and components.md:
 *   - External BUY → executes against the market maker's ASK (buyer buys FROM MM)
 *   - External SELL → executes against the market maker's BID (seller sells TO MM)
 *   - An order executes only if it crosses the active quote
 *   - Orders may partially fill against remaining quote quantity
 *   - An expired quote is not eligible for execution
 *   - Fills reference the quote_id that was executed
 *   - Partial fills decrement the remaining executable quantity
 *   - A fill is immutable once created
 */
class FillOrderDispatcherTest {

    private Repository<String, Quote> quoteRepository;
    private FillSender fillSender;
    private FillOrderDispatcher dispatcher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        quoteRepository = mock(Repository.class);
        fillSender = mock(FillSender.class);
        dispatcher = new FillOrderDispatcher(quoteRepository, fillSender);
    }

    // BUY orders match against ASK

    @Test
    void buyOrderFillsAtAskPrice() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(101.0, captor.getValue().price(), "BUY fills at ask price");
    }

    @Test
    void buyOrderAcceptedWhenLimitAboveAsk() {
        // Buyer willing to pay more than ask -> still fills at ask
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 110.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(101.0, captor.getValue().price(), "Fills at ask, not at limit");
    }

    @Test
    void buyOrderRejectedWhenLimitBelowAsk() {
        // Buyer only willing to pay 100, but ask is 101 -> no cross
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 100.0, Side.BUY);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    @Test
    void buyFillDecrementsAskQuantityOnly() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 4, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Quote> captor = ArgumentCaptor.forClass(Quote.class);
        verify(quoteRepository).put(captor.capture());
        assertEquals(6, captor.getValue().askQuantity(), "Ask consumed: 10 - 4 = 6");
        assertEquals(10, captor.getValue().bidQuantity(), "Bid untouched");
    }

    // SELL orders match against BID
    @Test
    void sellOrderFillsAtBidPrice() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 99.0, Side.SELL);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(99.0, captor.getValue().price(), "SELL fills at bid price");
    }

    @Test
    void sellOrderAcceptedWhenLimitBelowBid() {
        // Seller willing to accept less than bid -> still fills at bid
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 90.0, Side.SELL);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(99.0, captor.getValue().price(), "Fills at bid, not at limit");
    }

    @Test
    void sellOrderRejectedWhenLimitAboveBid() {
        // Seller wants at least 100, but bid is only 99 -> no cross
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 100.0, Side.SELL);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    @Test
    void sellFillDecrementsBidQuantityOnly() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 7, 99.0, Side.SELL);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Quote> captor = ArgumentCaptor.forClass(Quote.class);
        verify(quoteRepository).put(captor.capture());
        assertEquals(3, captor.getValue().bidQuantity(), "Bid consumed: 10 - 7 = 3");
        assertEquals(10, captor.getValue().askQuantity(), "Ask untouched");
    }

    // Partial fills — "Orders may partially fill against the remaining quote quantity"

    @Test
    void buyPartialFillWhenOrderExceedsAskQuantity() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 3);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 10, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(3, captor.getValue().quantity(), "Capped at remaining ask qty");
    }

    @Test
    void sellPartialFillWhenOrderExceedsBidQuantity() {
        Quote quote = makeQuote("AAPL", 99.0, 3, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 10, 99.0, Side.SELL);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(3, captor.getValue().quantity(), "Capped at remaining bid qty");
    }

    @Test
    void exactFillWhenOrderEqualsQuoteQuantity() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 5);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> fillCaptor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(fillCaptor.capture());
        assertEquals(5, fillCaptor.getValue().quantity());

        ArgumentCaptor<Quote> quoteCaptor = ArgumentCaptor.forClass(Quote.class);
        verify(quoteRepository).put(quoteCaptor.capture());
        assertEquals(0, quoteCaptor.getValue().askQuantity(), "Ask fully consumed");
    }

    // Expiration

    @Test
    void expiredQuoteRejectsBuyOrder() {
        Quote expired = new Quote("AAPL", 99.0, 10, 101.0, 10,
                UUID.randomUUID(), System.currentTimeMillis() - 1000);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(expired));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    @Test
    void expiredQuoteRejectsSellOrder() {
        Quote expired = new Quote("AAPL", 99.0, 10, 101.0, 10,
                UUID.randomUUID(), System.currentTimeMillis() - 1000);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(expired));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 99.0, Side.SELL);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    // No quote, no actuve symbol

    @Test
    void noQuoteRejectsBuyOrder() {
        when(quoteRepository.get("NOPE")).thenReturn(Optional.empty());

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "NOPE", 5, 101.0, Side.BUY);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    @Test
    void noQuoteRejectsSellOrder() {
        when(quoteRepository.get("NOPE")).thenReturn(Optional.empty());

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "NOPE", 5, 99.0, Side.SELL);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    // Zero remaining quantity, side already fully consumed

    @Test
    void zeroAskQuantityRejectsBuy() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 0);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    @Test
    void zeroBidQuantityRejectsSell() {
        Quote quote = makeQuote("AAPL", 99.0, 0, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 99.0, Side.SELL);

        assertThrows(OrderValidationException.class, () -> dispatcher.dispatchOrder(order));
        verify(fillSender, never()).sendFill(any());
    }

    // Fill metadata, "Fills reference the quote_id that was executed" and contain correct fields

    @Test
    void fillReferencesQuoteId() {
        UUID quoteId = UUID.randomUUID();
        Quote quote = new Quote("AAPL", 99.0, 10, 101.0, 10, quoteId,
                System.currentTimeMillis() + 30_000);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(quoteId, captor.getValue().quoteId(), "Fill must reference the executed quote");
    }

    @Test
    void fillContainsCorrectOrderId() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        UUID orderId = UUID.randomUUID();
        ExternalOrder order = new ExternalOrder(orderId, "AAPL", 5, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals(orderId, captor.getValue().orderId());
    }

    @Test
    void fillContainsCorrectSymbolAndSide() {
        Quote quote = makeQuote("GOOG", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("GOOG")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "GOOG", 5, 99.0, Side.SELL);
        dispatcher.dispatchOrder(order);

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertEquals("GOOG", captor.getValue().symbol());
        assertEquals(Side.BUY, captor.getValue().side());
    }

    @Test
    void fillHasTimestamp() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        long before = System.currentTimeMillis();
        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);
        long after = System.currentTimeMillis();

        ArgumentCaptor<Fill> captor = ArgumentCaptor.forClass(Fill.class);
        verify(fillSender).sendFill(captor.capture());
        assertTrue(captor.getValue().createdAt() >= before && captor.getValue().createdAt() <= after);
    }

    // Quote update happens before fill is sent

    @Test
    void quoteUpdatedBeforeFillSent() {
        Quote quote = makeQuote("AAPL", 99.0, 10, 101.0, 10);
        when(quoteRepository.get("AAPL")).thenReturn(Optional.of(quote));

        ExternalOrder order = new ExternalOrder(UUID.randomUUID(), "AAPL", 5, 101.0, Side.BUY);
        dispatcher.dispatchOrder(order);

        InOrder inOrder = inOrder(quoteRepository, fillSender);
        inOrder.verify(quoteRepository).put(any(Quote.class));
        inOrder.verify(fillSender).sendFill(any(Fill.class));
    }


    private Quote makeQuote(String symbol, double bid, int bidQty, double ask, int askQty) {
        return new Quote(symbol, bid, bidQty, ask, askQty, UUID.randomUUID(),
                System.currentTimeMillis() + 30_000);
    }
}
package edu.yu.velocitytrading.marketmaker;

import edu.yu.velocitytrading.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for MarketMaker
 *
 * From components.md:
 *   - Routes incoming position snapshots to the quote generator
 *   - Filters snapshots to only managed symbols
 *   - Deduplicates by version to prevent reprocessing the same position state
 *   - Passes the fill from each snapshot to the quote generator for inventory skew
 */
class MarketMakerUnitTest {

    private SnapshotTracker snapshotTracker;
    private QuoteGenerator quoteGenerator;
    private MarketMaker marketMaker;

    @BeforeEach
    void setUp() {
        snapshotTracker = mock(SnapshotTracker.class);
        quoteGenerator = mock(QuoteGenerator.class);
        marketMaker = new MarketMaker(snapshotTracker, quoteGenerator);
    }

    private Position makePosition(String symbol, int netQty, long version) {
        return new Position(symbol, netQty, version, UUID.randomUUID());
    }

    private void runWith(StateSnapshot... snapshots) throws Exception {
        when(snapshotTracker.getPositions()).thenReturn(Flux.just(snapshots));
        marketMaker.run(null);
    }

    // --- Symbol filtering ---

    /**
     * The primary happy-path: when a snapshot arrives for a symbol that the market maker
     * is configured to manage, it must forward the position and fill to the quote generator
     * to produce and publish a new quote.
     */
    @Test
    void callsGenerateQuoteForManagedSymbol() throws Exception {
        Position pos = makePosition("AAPL", 0, 1L);
        StateSnapshot snapshot = new StateSnapshot(pos, null);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        runWith(snapshot);

        verify(quoteGenerator).generateQuote(pos, null);
    }

    /**
     * Snapshots may arrive for symbols that this market maker node does not manage
     * (e.g. they belong to a different node in a sharded deployment). Those snapshots
     * must be silently ignored so the node only quotes its own assigned symbols.
     */
    @Test
    void doesNotCallGenerateQuoteForUnmanagedSymbol() throws Exception {
        Position pos = makePosition("AAPL", 0, 1L);
        StateSnapshot snapshot = new StateSnapshot(pos, null);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(false);
        runWith(snapshot);

        verify(quoteGenerator, never()).generateQuote(any(), any());
    }

    // --- Null safety ---

    /**
     * A snapshot with a null position field is malformed and carries no actionable data.
     * The market maker must guard against this and return without calling the quote generator,
     * rather than propagating a NullPointerException downstream.
     */
    @Test
    void ignoresSnapshotWithNullPosition() throws Exception {
        StateSnapshot snapshot = new StateSnapshot(null, null);
        when(snapshotTracker.getPositions()).thenReturn(Flux.just(snapshot));

        marketMaker.run(null);

        verify(quoteGenerator, never()).generateQuote(any(), any());
    }

    /**
     * A position with a null symbol cannot be routed to the correct quote generator or
     * matched against the managed symbol list. The market maker must skip it without
     * throwing, since a null symbol check would otherwise cause a NullPointerException
     * in the handlesSymbol lookup.
     */
    @Test
    void ignoresSnapshotWithNullSymbol() throws Exception {
        Position pos = new Position(null, 0, 1L, null);
        StateSnapshot snapshot = new StateSnapshot(pos, null);
        when(snapshotTracker.getPositions()).thenReturn(Flux.just(snapshot));

        marketMaker.run(null);

        verify(quoteGenerator, never()).generateQuote(any(), any());
    }

    // --- Version deduplication ---

    /**
     * The very first snapshot seen for a symbol has no prior version to compare against,
     * so it must always be processed. This is the baseline case for version tracking.
     */
    @Test
    void processesFirstSnapshotForSymbol() throws Exception {
        Position pos = makePosition("AAPL", 0, 1L);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        runWith(new StateSnapshot(pos, null));

        verify(quoteGenerator, times(1)).generateQuote(pos, null);
    }

    /**
     * If two snapshots arrive with the same version number for the same symbol (e.g. due to
     * a retry or duplicate delivery), only the first should trigger quote generation. Processing
     * the same position state twice would produce redundant quotes and waste exposure capacity.
     */
    @Test
    void doesNotProcessSameVersionTwice() throws Exception {
        Position pos1 = makePosition("AAPL", 0, 5L);
        Position pos2 = makePosition("AAPL", 0, 5L);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        runWith(new StateSnapshot(pos1, null), new StateSnapshot(pos2, null));

        verify(quoteGenerator, times(1)).generateQuote(any(), any());
    }

    /**
     * When a snapshot with a strictly higher version arrives after a previous one has been
     * processed, it represents a genuine new position state and must be forwarded to the
     * quote generator. Both version-1 and version-2 snapshots should each produce a quote.
     */
    @Test
    void processesNewerVersionAfterOlder() throws Exception {
        Position pos1 = makePosition("AAPL", 0, 1L);
        Position pos2 = makePosition("AAPL", 5, 2L);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        runWith(new StateSnapshot(pos1, null), new StateSnapshot(pos2, null));

        verify(quoteGenerator, times(2)).generateQuote(any(), any());
    }

    /**
     * If an older snapshot arrives after a newer one has already been processed (e.g. due to
     * out-of-order delivery), it must be skipped. Acting on a stale position state could
     * generate an incorrect quote based on outdated inventory information.
     */
    @Test
    void skipsOlderVersionAfterNewerHasBeenProcessed() throws Exception {
        Position newer = makePosition("AAPL", 0, 10L);
        Position older = makePosition("AAPL", 0, 5L);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        runWith(new StateSnapshot(newer, null), new StateSnapshot(older, null));

        verify(quoteGenerator, times(1)).generateQuote(any(), any());
    }

    // --- Multiple symbols are independent ---

    /**
     * When snapshots for two different managed symbols arrive in the same stream, both must
     * be processed independently. The market maker should generate one quote per symbol,
     * not skip the second because it saw something for a different symbol first.
     */
    @Test
    void processesEachManagedSymbolIndependently() throws Exception {
        Position aaplPos = makePosition("AAPL", 0, 1L);
        Position googPos = makePosition("GOOG", 0, 1L);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        when(snapshotTracker.handlesSymbol("GOOG")).thenReturn(true);
        runWith(new StateSnapshot(aaplPos, null), new StateSnapshot(googPos, null));

        verify(quoteGenerator, times(2)).generateQuote(any(), any());
    }

    /**
     * Version tracking is per-symbol, not global. Seeing version 1 for AAPL must not prevent
     * version 1 for GOOG from being processed. A duplicate version for AAPL should still be
     * skipped, but a first-seen version for GOOG at the same number should go through.
     */
    @Test
    void versionDeduplicationIsTrackedPerSymbol() throws Exception {
        // Version 1 for both symbols should each be processed once
        Position aaplV1 = makePosition("AAPL", 0, 1L);
        Position googV1 = makePosition("GOOG", 0, 1L);
        Position aaplV1Duplicate = makePosition("AAPL", 0, 1L);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        when(snapshotTracker.handlesSymbol("GOOG")).thenReturn(true);
        when(snapshotTracker.getPositions()).thenReturn(Flux.just(
                new StateSnapshot(aaplV1, null),
                new StateSnapshot(googV1, null),
                new StateSnapshot(aaplV1Duplicate, null)
        ));
        marketMaker.run(null);

        verify(quoteGenerator, times(2)).generateQuote(any(), any());
    }

    /**
     * An unmanaged symbol should be filtered out, but its presence in the stream must not
     * interfere with the processing of a subsequent managed symbol. Both symbols' snapshots
     * should be evaluated independently; only the managed one produces a quote.
     */
    @Test
    void unmanagedSymbolDoesNotBlockVersionTrackingForOtherSymbols() throws Exception {
        Position aaplPos = makePosition("AAPL", 0, 1L);
        Position googPos = makePosition("GOOG", 0, 1L);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(false);
        when(snapshotTracker.handlesSymbol("GOOG")).thenReturn(true);
        runWith(new StateSnapshot(aaplPos, null), new StateSnapshot(googPos, null));

        verify(quoteGenerator, times(1)).generateQuote(any(), any());
    }

    // --- Fill is passed through ---

    /**
     * The fill attached to a snapshot is what drives inventory-aware price skew in the quote
     * generator. The market maker must pass it through unchanged so the generator can apply
     * the correct directional adjustment to the reference price and quantities.
     */
    @Test
    void passesFillFromSnapshotToQuoteGenerator() throws Exception {
        Fill fill = new Fill(UUID.randomUUID(), "AAPL", Side.BUY, 10, 100.0, UUID.randomUUID(), System.currentTimeMillis());
        Position pos = makePosition("AAPL", 0, 1L);
        StateSnapshot snapshot = new StateSnapshot(pos, fill);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        runWith(snapshot);

        verify(quoteGenerator).generateQuote(pos, fill);
    }

    /**
     * When a snapshot has no associated fill (e.g. a position refresh with no new trade),
     * the market maker must pass null as the fill argument. The quote generator uses null
     * fill as the signal to skip skew and use the existing quote or default prices instead.
     */
    @Test
    void passesNullFillWhenSnapshotHasNoFill() throws Exception {
        Position pos = makePosition("AAPL", 0, 1L);
        StateSnapshot snapshot = new StateSnapshot(pos, null);

        when(snapshotTracker.handlesSymbol("AAPL")).thenReturn(true);
        runWith(snapshot);

        verify(quoteGenerator).generateQuote(pos, null);
    }
}

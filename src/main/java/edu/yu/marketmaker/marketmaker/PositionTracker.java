package edu.yu.marketmaker.marketmaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Component;

import edu.yu.marketmaker.model.StateSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!test-position-tracker")
public class PositionTracker implements SnapshotTracker {

    private static final Logger log = LoggerFactory.getLogger(PositionTracker.class);
    private static final String TRADING_STATE_HOST = "trading-state";
    private static final int TRADING_STATE_RSOCKET_PORT = 7000;
    private static final Duration STREAM_RECONNECT_DELAY = Duration.ofSeconds(2);

    // Thread-safe set of symbols we're tracking. Use ConcurrentHashMap's keySet for efficiency.
    private final Set<String> trackedSymbols = ConcurrentHashMap.newKeySet();

    private final RSocketRequester.Builder rsocketBuilder;

    public PositionTracker(RSocketRequester.Builder rsocketRequesterBuilder) {
        this.rsocketBuilder = rsocketRequesterBuilder;
    }

    @Override
    public boolean addSymbol(String symbol) {
        if (symbol == null) return false;
        return trackedSymbols.add(symbol);
    }

    @Override
    public boolean removeSymbol(String symbol) {
        if (symbol == null) return false;
        return trackedSymbols.remove(symbol);
    }

    @Override
    public boolean handlesSymbol(String symbol) {
        if (symbol == null) return false;
        return trackedSymbols.contains(symbol);
    }

    @Override
    public Set<String> handledSymbols() {
        return Set.copyOf(trackedSymbols);
    }

    public Flux<StateSnapshot> getPositions() {
        // Flux.defer + a fresh requester per attempt so retries actually
        // reconnect instead of riding a dead TCP connection. Mirrors the
        // pattern in LeaderForwarder; without it the subscription terminates
        // on the first network blip and the MM stops generating quotes.
        return Flux.defer(() ->
                        rsocketBuilder.tcp(TRADING_STATE_HOST, TRADING_STATE_RSOCKET_PORT)
                                .route("state.stream")
                                .retrieveFlux(StateSnapshot.class))
                .filter(Objects::nonNull)
                .filter(snapshot -> snapshot.position() != null)
                .filter(snapshot -> snapshot.position().symbol() != null)
                .filter(snapshot -> handlesSymbol(snapshot.position().symbol()))
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, STREAM_RECONNECT_DELAY)
                        .doBeforeRetry(sig -> log.warn(
                                "state.stream subscription error, retrying: {}",
                                sig.failure().toString())))
                // Hop off the RSocket event loop. Downstream calls
                // ProductionQuoteGenerator.generateQuote which uses .block()
                // for the reservation roundtrip — Reactor refuses blocking
                // on netty's epoll threads.
                .publishOn(Schedulers.boundedElastic());
    }
}
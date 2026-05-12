package edu.yu.marketmaker.marketmaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only fault injector for error case 10 (market maker crashes during
 * the quote replacement cycle, after releasing the old reservation but before
 * acquiring the new one).
 *
 * <p>This bean only exists when the {@code fault-injection} Spring profile is
 * active. {@link ProductionQuoteGenerator} consumes it via optional injection;
 * when the profile is absent (the default) the field is {@code null} and the
 * production code path is unaffected.
 *
 * <p>Even with the profile active, the injector is a no-op until something
 * explicitly arms it via {@link FaultInjectionController}. Arming sets a
 * single-shot pending symbol; the next call to {@link #consumeIfArmed(String)}
 * for that symbol returns {@code true} and clears the state, so the crash
 * can only fire once per arm.
 *
 * <p>Two independent safety gates therefore stand between this code and a
 * production deployment:
 * <ol>
 *   <li>The {@code fault-injection} profile must be in
 *       {@code SPRING_PROFILES_ACTIVE}.</li>
 *   <li>An operator must POST to the arm endpoint with a specific symbol.</li>
 * </ol>
 */
@Component
@Profile("fault-injection")
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    private final AtomicReference<String> armedSymbol = new AtomicReference<>(null);

    /**
     * Arm the injector to crash the next time the quote generator processes
     * a replacement cycle for {@code symbol}. Overwrites any prior armed
     * symbol; passing {@code null} disarms.
     */
    public void armQuoteReplaceCrash(String symbol) {
        String previous = armedSymbol.getAndSet(symbol);
        if (previous != null) {
            log.warn("[FAULT-INJECTION] re-arm: overwriting previously armed symbol {} with {}",
                    previous, symbol);
        } else {
            log.warn("[FAULT-INJECTION] armed: will crash on next quote-replace cycle for symbol={}",
                    symbol);
        }
    }

    /** @return the currently armed symbol, or {@code null} if disarmed. */
    public String currentlyArmedSymbol() {
        return armedSymbol.get();
    }

    /**
     * Consume the armed flag if it matches {@code symbol}.
     *
     * <p>Returns {@code true} (and clears the armed state) only when the
     * injector was armed for exactly this symbol. After a successful consume
     * the caller is expected to release the old reservation and halt the JVM
     * — see {@link ProductionQuoteGenerator}.
     */
    public synchronized boolean consumeIfArmed(String symbol) {
        if (symbol == null) {
            return false;
        }
        // Value comparison, not reference: AtomicReference.compareAndSet
        // uses == under the hood, which fails for equal-but-distinct String
        // instances (the symbol from HTTP query-string vs. the one from a
        // deserialized Position payload). equals() is what we actually want.
        if (symbol.equals(armedSymbol.get())) {
            armedSymbol.set(null);
            return true;
        }
        return false;
    }
}
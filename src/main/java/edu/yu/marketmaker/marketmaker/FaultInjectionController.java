package edu.yu.marketmaker.marketmaker;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Test-only REST surface for {@link FaultInjector}. Only registered when the
 * {@code fault-injection} Spring profile is active.
 *
 * <p>Used exclusively by the error-case 10 integration tests
 * ({@code LocalError10MMCrashDuringQuoteReplaceTest} /
 * {@code ClusterError10MMCrashDuringQuoteReplaceTest}). No production code
 * path arms the injector.
 */
@RestController
@RequestMapping("/test/fault-injection")
@Profile("fault-injection")
public class FaultInjectionController {

    private final FaultInjector injector;

    public FaultInjectionController(FaultInjector injector) {
        this.injector = injector;
    }

    /**
     * Arm the injector to crash this market-maker the next time it processes
     * a quote replacement cycle for {@code symbol}. The crash releases the
     * old reservation first (matching error case 10's documented sequence)
     * and then hard-halts the JVM via {@link Runtime#halt(int)}.
     *
     * <p>Idempotent: re-arming overwrites any prior armed symbol.
     */
    @PostMapping("/arm-quote-replace-crash")
    public ResponseEntity<ArmedStatus> armQuoteReplaceCrash(@RequestParam("symbol") String symbol) {
        injector.armQuoteReplaceCrash(symbol);
        return ResponseEntity.ok(new ArmedStatus(symbol));
    }

    /** Inspect the currently armed symbol, if any. */
    @GetMapping("/status")
    public ResponseEntity<ArmedStatus> status() {
        return ResponseEntity.ok(new ArmedStatus(injector.currentlyArmedSymbol()));
    }

    public record ArmedStatus(String armedSymbol) {}
}
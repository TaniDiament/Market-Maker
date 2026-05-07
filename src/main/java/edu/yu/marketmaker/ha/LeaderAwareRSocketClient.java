package edu.yu.marketmaker.ha;

import io.rsocket.transport.netty.client.TcpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic leader-aware RSocket client.
 *
 * <p>Encapsulates the pattern every caller needs:
 * <ol>
 *   <li>Resolve the current leader of service {@code X} via {@link ServiceRegistry}</li>
 *   <li>Reuse a cached {@link RSocketRequester} keyed by {@code host:port}</li>
//  *   <li>On send failure, evict the cached requester so the next call re-resolves
 *       (handles failover — the new leader will have a different address)</li>
 * </ol>
 *
 * <p>Usage from any service:
 * <pre>{@code
 * @Component
 * class FillSender {
 *     private final LeaderAwareRSocketClient client;
 *
 *     public FillSender(LeaderAwareRSocketClient client) { this.client = client; }
 *
 *     public void sendFill(Fill fill) {
 *         client.send("trading-state", "state.fills", fill);
 *     }
 * }
 * }</pre>
 */
@Component
public class LeaderAwareRSocketClient {

    private static final Logger log = LoggerFactory.getLogger(LeaderAwareRSocketClient.class);

    private final ServiceRegistry registry;
    private final RSocketRequester.Builder builder;

    /** host:port -> requester. Evicted on send failure. */
    private final ConcurrentHashMap<String, RSocketRequester> requesters = new ConcurrentHashMap<>();

    public LeaderAwareRSocketClient(ServiceRegistry registry, RSocketRequester.Builder builder) {
        this.registry = registry;
        this.builder = builder;
    }

    // ---------- Fire-and-forget ----------

    /**
     * Send a payload to the current leader of {@code serviceName} on {@code route}.
     * Fire-and-forget; failures log and evict the cached requester but don't throw.
     */
    public void send(String serviceName, String route, Object payload) {
        RequesterOrMissing r = resolve(serviceName);
        if (r.missing) return;

        r.requester.route(route)
                .data(payload)
                .send()
                .doOnError(e -> evict(serviceName, r.key, e))
                .subscribe();
    }

    // ---------- Request-response ----------

    /**
     * Request-response to the current leader of {@code serviceName}. On failure,
     * evicts the cached requester and propagates the error so callers can retry.
     */
    public <T> Mono<T> requestResponse(String serviceName, String route, Object payload, Class<T> responseType) {
        return Mono.defer(() -> {
            RequesterOrMissing r = resolve(serviceName);
            if (r.missing) {
                return Mono.error(new IllegalStateException(
                        "No leader for service '" + serviceName + "'"));
            }
            return r.requester.route(route)
                    .data(payload)
                    .retrieveMono(responseType)
                    .doOnError(e -> evict(serviceName, r.key, e));
        });
    }

    // ---------- Request-stream ----------

    /**
     * Subscribe to a stream from the current leader of {@code serviceName}.
     *
     * <p>Wrap the returned Flux with {@code .repeatWhen(...)} at the call site for
     * automatic reconnection on leader change — each repeat triggers a fresh
     * {@code resolve()} and picks up whoever is leader at that moment.
     */
    public <T> Flux<T> requestStream(String serviceName, String route, Class<T> responseType) {
        return Flux.defer(() -> {
            RequesterOrMissing r = resolve(serviceName);
            if (r.missing) {
                return Flux.error(new IllegalStateException(
                        "No leader for service '" + serviceName + "'"));
            }
            return r.requester.route(route)
                    .retrieveFlux(responseType)
                    .doOnError(e -> evict(serviceName, r.key, e));
        });
    }

    /**
     * Same as {@link #requestStream} but sends a request payload.
     */
    public <T> Flux<T> requestStream(String serviceName, String route, Object payload, Class<T> responseType) {
        return Flux.defer(() -> {
            RequesterOrMissing r = resolve(serviceName);
            if (r.missing) {
                return Flux.error(new IllegalStateException(
                        "No leader for service '" + serviceName + "'"));
            }
            return r.requester.route(route)
                    .data(payload)
                    .retrieveFlux(responseType)
                    .doOnError(e -> evict(serviceName, r.key, e));
        });
    }

    // ---------- Internals ----------

    private RequesterOrMissing resolve(String serviceName) {
        Optional<ServiceRegistry.Endpoint> leader = registry.getLeaderAddress(serviceName);
        if (leader.isEmpty()) {
            log.warn("No leader registered for service '{}'", serviceName);
            return RequesterOrMissing.MISSING;
        }
        ServiceRegistry.Endpoint ep = leader.get();
        if (ep.rsocketPort <= 0) {
            log.error("Leader for '{}' at {} has no RSocket port advertised", serviceName, ep.host);
            return RequesterOrMissing.MISSING;
        }
        String key = ep.host + ":" + ep.rsocketPort;
        RSocketRequester req = requesters.computeIfAbsent(key, k -> {
            log.info("Opening RSocket connection to {} leader at {}", serviceName, k);
            return builder.transport(TcpClientTransport.create(ep.host, ep.rsocketPort));
        });
        return new RequesterOrMissing(req, key);
    }

    private void evict(String serviceName, String key, Throwable cause) {
        log.warn("RSocket call to {} leader at {} failed ({}) — evicting cached requester",
                serviceName, key, cause.getMessage());
        requesters.remove(key);
    }

    private static final class RequesterOrMissing {
        static final RequesterOrMissing MISSING = new RequesterOrMissing(null, null);

        final RSocketRequester requester;
        final String key;
        final boolean missing;

        RequesterOrMissing(RSocketRequester requester, String key) {
            this.requester = requester;
            this.key = key;
            this.missing = (requester == null);
        }
    }
}

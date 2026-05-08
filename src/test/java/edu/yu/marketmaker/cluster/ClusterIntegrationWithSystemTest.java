package edu.yu.marketmaker.cluster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.yu.marketmaker.model.Fill;
import edu.yu.marketmaker.model.Side;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end system test that exercises every service in the compose stack:
 * postgres, zookeeper (3-node ensemble), trading-state, exposure-reservation,
 * exchange, external-publisher, and 7 market-maker nodes running the
 * {@code production-quote-generator} profile.
 *
 * Flow under test (happy path, no fault injection):
 * <pre>
 *   external-publisher  ──PUT /quotes──▶  exchange  ──RSocket──▶  exposure-reservation
 *           │                                │
 *           └──POST /orders──▶               ├──RSocket fill──▶  trading-state
 *                                            │                        │
 *                                            │                        └──state.stream──▶ leader MM
 *                                            │                                                │
 *                                            ▼                                                ▼
 *                                 (quote updated by MM via                       worker MM generates new
 *                                  shared Hazelcast `quotes` map)                 quote via ProductionQuoteGenerator
 * </pre>
 *
 * Assertions:
 * <ul>
 *   <li>Every seed symbol ends up with at least one fill recorded in
 *       trading-state (proves external-publisher → exchange → trading-state).</li>
 *   <li>At least one symbol's current quote in the exchange has a quoteId
 *       that is <em>not</em> in the bootstrap set produced by the publisher —
 *       proof that a market-maker node wrote a fresh quote back into the
 *       shared `quotes` map, closing the loop through all services.</li>
 * </ul>
 *
 * Opt-in: {@code -Dcluster.it=true}; docker must be running locally.
 */
@EnabledIfSystemProperty(named = "cluster.it", matches = "true")
class ClusterIntegrationWithSystemTest {

    /** Host port -> compose service name, for the 7 MM nodes. */
    private static final SortedMap<Integer, String> MM_PORT_TO_SERVICE;
    static {
        SortedMap<Integer, String> m = new TreeMap<>();
        m.put(8081, "market-maker-node-1");
        m.put(8082, "market-maker-node-2");
        m.put(8083, "market-maker-node-3");
        m.put(8084, "market-maker-node-4");
        m.put(8085, "market-maker-node-5");
        m.put(8086, "market-maker-node-6");
        m.put(8087, "market-maker-node-7");
        MM_PORT_TO_SERVICE = Collections.unmodifiableSortedMap(m);
    }

    private static final Set<String> SEED_SYMBOLS = new TreeSet<>(List.of(
            "AAPL", "MSFT", "GOOG", "TSLA", "NVDA", "AMZN", "META"));

    private static final int TRADING_STATE_PORT = 18080;
    private static final int EXCHANGE_PORT = 18081;
    private static final int EXPOSURE_RES_PORT = 18082;
    private static final int PUBLISHER_PORT = 18083;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PROJECT_ROOT = Path.of(".").toAbsolutePath().normalize();

    @BeforeAll
    static void bootStack() throws Exception {
        System.out.println("[E2E] cleaning any prior stack...");
        runDocker(null, TimeUnit.MINUTES.toMillis(3), "compose", "down", "-v", "--remove-orphans");

        System.out.println("[E2E] docker compose build (first run may take several minutes)...");
        int buildRc = runDocker(Map.of("QUOTE_GENERATOR_PROFILE", "production-quote-generator"),
                TimeUnit.MINUTES.toMillis(20),
                "compose", "build", "market-maker-node-1");
        assertEquals(0, buildRc, "docker compose build failed");

        System.out.println("[E2E] bringing up core infra (zk + postgres + trading-state + exposure-reservation)...");
        int rcCore = runDocker(Map.of("QUOTE_GENERATOR_PROFILE", "production-quote-generator"),
                TimeUnit.MINUTES.toMillis(5),
                "compose", "up", "-d",
                "zookeeper1", "zookeeper2", "zookeeper3", "postgres",
                "trading-state-1", "trading-state-2", "trading-state-3",
                "exposure-reservation-1", "exposure-reservation-2", "exposure-reservation-3",
                "service-lb");
        assertEquals(0, rcCore, "docker compose up (core) failed");

        awaitHealthy("trading-state", TRADING_STATE_PORT, Duration.ofMinutes(4));
        awaitHealthy("exposure-reservation", EXPOSURE_RES_PORT, Duration.ofMinutes(4));

        System.out.println("[E2E] bringing up exchange...");
        int rcExchange = runDocker(Map.of("QUOTE_GENERATOR_PROFILE", "production-quote-generator"),
                TimeUnit.MINUTES.toMillis(3),
                "compose", "up", "-d", "exchange-1", "exchange-2", "exchange-3");
        assertEquals(0, rcExchange, "docker compose up (exchange) failed");
        awaitHealthy("exchange", EXCHANGE_PORT, Duration.ofMinutes(4));

        System.out.println("[E2E] bringing up external-publisher...");
        int rcPub = runDocker(Map.of("QUOTE_GENERATOR_PROFILE", "production-quote-generator"),
                TimeUnit.MINUTES.toMillis(3),
                "compose", "up", "-d", "external-publisher");
        assertEquals(0, rcPub, "docker compose up (external-publisher) failed");
        awaitHealthy("external-publisher", PUBLISHER_PORT, Duration.ofMinutes(4));

        System.out.println("[E2E] bringing up market-maker nodes (production-quote-generator profile)...");
        List<String> upCmd = new ArrayList<>(List.of("compose", "up", "-d"));
        upCmd.addAll(MM_PORT_TO_SERVICE.values());
        int rcMm = runDocker(Map.of("QUOTE_GENERATOR_PROFILE", "production-quote-generator"),
                TimeUnit.MINUTES.toMillis(5),
                upCmd.toArray(String[]::new));
        assertEquals(0, rcMm, "docker compose up (market-maker nodes) failed");

        System.out.println("[E2E] waiting for 7-node cluster convergence...");
        awaitCondition(Duration.ofMinutes(8), ClusterIntegrationWithSystemTest::allNodesConverged,
                "cluster did not converge within 8 minutes");
        System.out.println("[E2E] full stack up.");
    }

    @AfterAll
    static void teardownStack() throws Exception {
        System.out.println("[E2E] docker compose down -v");
        runDocker(null, TimeUnit.MINUTES.toMillis(2), "compose", "down", "-v");
    }

    /**
     * Seed quotes, drive orders, verify the loop closes: trading-state has
     * fills for every symbol, and the exchange eventually serves a quote
     * whose id was never issued by the publisher — i.e. a market-maker
     * wrote it back into the shared Hazelcast map.
     */
    @Test
    void ordersFlowThroughEntireSystemAndMarketMakersProduceQuotes() throws Exception {
        System.out.println("[E2E] seeding bootstrap quotes via external-publisher...");
        Set<UUID> bootstrapQuoteIds = new HashSet<>(seedQuotes(new ArrayList<>(SEED_SYMBOLS)));
        assertEquals(SEED_SYMBOLS.size(), bootstrapQuoteIds.size(),
                "publisher must return one quoteId per symbol");

        Set<String> symbolsWithFills = new TreeSet<>();
        Set<String> symbolsWithMmQuote = new TreeSet<>();

        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        int wave = 0;
        while (Instant.now().isBefore(deadline)) {
            wave++;
            int accepted = submitOrders(new ArrayList<>(SEED_SYMBOLS), 25);
            System.out.println("[E2E] wave " + wave + ": exchange accepted " + accepted + " orders");

            for (String symbol : SEED_SYMBOLS) {
                if (!symbolsWithFills.contains(symbol) && hasNonZeroPosition(symbol)) {
                    symbolsWithFills.add(symbol);
                }
                if (!symbolsWithMmQuote.contains(symbol)) {
                    UUID currentId = currentExchangeQuoteId(symbol);
                    if (currentId != null && !bootstrapQuoteIds.contains(currentId)) {
                        symbolsWithMmQuote.add(symbol);
                    }
                }
            }

            if (symbolsWithFills.equals(SEED_SYMBOLS) && !symbolsWithMmQuote.isEmpty()) {
                break;
            }
            Thread.sleep(1500);
        }

        System.out.println("[E2E] symbols with fills: " + symbolsWithFills);
        System.out.println("[E2E] symbols with MM-generated quote in exchange: " + symbolsWithMmQuote);

        assertEquals(SEED_SYMBOLS, symbolsWithFills,
                "every seed symbol must have at least one fill in trading-state; "
                        + "proves external-publisher → exchange → trading-state wiring");
        assertFalse(symbolsWithMmQuote.isEmpty(),
                "at least one symbol must have a current exchange quote whose quoteId "
                        + "is not in the bootstrap set; proves a market-maker wrote a quote "
                        + "back via the shared Hazelcast quotes map. bootstrap ids="
                        + bootstrapQuoteIds);

        List<Fill> allFills = getAllFills();
        assertFalse(allFills.isEmpty(), "trading-state /state/fills returned no fills");

        Set<String> symbolsSeenInFills = new TreeSet<>();
        Map<String, Long> signedNetBySymbolFromFills = new TreeMap<>();
        Set<UUID> quoteIdsSeenInFills = new HashSet<>();
        for (Fill fill : allFills) {
            assertNotNull(fill.orderId(), "fill orderId must be present: " + fill);
            assertTrue(SEED_SYMBOLS.contains(fill.symbol()),
                    "fill symbol must be one of seed symbols: " + fill.symbol());
            assertNotNull(fill.side(), "fill side must be present: " + fill);
            assertTrue(fill.quantity() > 0, "fill quantity must be > 0: " + fill);
            assertTrue(fill.price() > 0.0, "fill price must be > 0: " + fill);
            assertNotNull(fill.quoteId(), "fill quoteId must be present: " + fill);
            assertTrue(fill.createdAt() > 0, "fill createdAt must be positive: " + fill);

            if (fill.side() == Side.BUY) {
                assertTrue(fill.price() >= 99.0,
                        "BUY fills must execute at/beyond the SELL limit (>=99.0): " + fill);
            } else {
                assertTrue(fill.price() <= 101.0,
                        "SELL fills must execute at/below the BUY limit (<=101.0): " + fill);
            }

            symbolsSeenInFills.add(fill.symbol());
            quoteIdsSeenInFills.add(fill.quoteId());
            long signed = fill.side() == Side.BUY ? fill.quantity() : -fill.quantity();
            signedNetBySymbolFromFills.merge(fill.symbol(), signed, Long::sum);
        }

        assertEquals(SEED_SYMBOLS, symbolsSeenInFills,
                "every seed symbol must appear in /state/fills at least once");
        assertEquals(SEED_SYMBOLS, signedNetBySymbolFromFills.keySet(),
                "net position must be derivable from fills for every seed symbol");
        assertTrue(quoteIdsSeenInFills.stream().anyMatch(id -> !bootstrapQuoteIds.contains(id)),
                "fills should include at least one market-maker quoteId not in bootstrap set");
    }

    // ---------- helpers ----------

    private static boolean allNodesConverged() {
        int responding = 0;
        Set<String> leaders = new HashSet<>();
        for (int port : MM_PORT_TO_SERVICE.keySet()) {
            JsonNode status = clusterStatusOrNull(port);
            if (status == null) return false;
            responding++;
            String lid = status.path("leaderId").asText(null);
            if (lid == null) return false;
            leaders.add(lid);
            if (status.path("members").size() != MM_PORT_TO_SERVICE.size()) return false;
        }
        return responding == MM_PORT_TO_SERVICE.size() && leaders.size() == 1;
    }

    private static JsonNode clusterStatusOrNull(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/cluster/status"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return JSON.readTree(resp.body());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Poll {@code GET /health} on {@code port} until 200, dumping the service's
     * container logs and status and failing loudly if it never becomes ready.
     */
    private static void awaitHealthy(String serviceName, int port, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (healthy(port)) {
                System.out.println("[E2E] " + serviceName + " healthy on port " + port);
                return;
            }
            Thread.sleep(2000);
        }
        System.err.println("[E2E] " + serviceName + " did not respond on /health within " + timeout);
        System.err.println("---- docker compose ps " + serviceName + " ----");
        System.err.println(runDockerCapturing(TimeUnit.SECONDS.toMillis(30),
                "compose", "ps", serviceName));
        System.err.println("---- docker compose logs --tail 300 " + serviceName + " ----");
        System.err.println(runDockerCapturing(TimeUnit.MINUTES.toMillis(1),
                "compose", "logs", "--tail", "300", serviceName));
        System.err.println("---- end logs ----");
        throw new AssertionError(serviceName + " not healthy within " + timeout);
    }

    /** Runs {@code docker <args>} capturing combined stdout/stderr to a string. */
    private static String runDockerCapturing(long timeoutMs, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(PROJECT_ROOT.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            output.append("[timed out waiting for docker command]\n");
        }
        return output.toString();
    }

    private static boolean healthy(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<UUID> seedQuotes(List<String> symbols) throws Exception {
        String body = JSON.writeValueAsString(symbols);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PUBLISHER_PORT + "/publisher/seed-quotes"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            fail("seed-quotes returned " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.readValue(resp.body(), new TypeReference<List<UUID>>() {});
    }

    private static int submitOrders(List<String> symbols, int count) throws Exception {
        String body = JSON.writeValueAsString(symbols);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PUBLISHER_PORT
                        + "/publisher/submit-orders?count=" + count))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            fail("submit-orders returned " + resp.statusCode() + ": " + resp.body());
        }
        return Integer.parseInt(resp.body().trim());
    }

    private static List<Fill> getAllFills() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TRADING_STATE_PORT + "/state/fills"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            fail("GET /state/fills returned " + resp.statusCode() + ": " + resp.body());
        }
        // /state/fills may include storage-level metadata fields (e.g. "id").
        return JSON.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(resp.body(), new TypeReference<List<Fill>>() {});
    }

    private static boolean hasNonZeroPosition(String symbol) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TRADING_STATE_PORT + "/positions/" + symbol))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            JsonNode node = JSON.readTree(resp.body());
            // Optional<Position> serializes as {"present":true,"value":{...}} or as the
            // payload directly depending on Jackson config; handle both shapes.
            JsonNode pos = node.has("value") ? node.path("value") : node;
            if (pos.isMissingNode() || pos.isNull()) return false;
            JsonNode netQty = pos.path("netQuantity");
            return !netQty.isMissingNode() && netQty.asLong(0) != 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static UUID currentExchangeQuoteId(String symbol) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + EXCHANGE_PORT + "/quotes/" + symbol))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode node = JSON.readTree(resp.body());
            String id = node.path("quoteId").asText(null);
            return id == null ? null : UUID.fromString(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static int runDocker(Map<String, String> env, long timeoutMs, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(PROJECT_ROOT.toFile())
                .redirectErrorStream(true)
                .inheritIO();
        if (env != null) {
            pb.environment().putAll(env);
        }
        Process p = pb.start();
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("timeout running: " + String.join(" ", cmd));
        }
        return p.exitValue();
    }

    private static void awaitCondition(Duration timeout, BooleanSupplier condition, String failureMessage) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(failureMessage);
    }
}

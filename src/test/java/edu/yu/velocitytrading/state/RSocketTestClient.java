package edu.yu.velocitytrading.state;

import edu.yu.velocitytrading.model.Fill;
import edu.yu.velocitytrading.model.Position;
import edu.yu.velocitytrading.model.Side;
import edu.yu.velocitytrading.model.StateSnapshot;
import io.rsocket.transport.netty.client.TcpClientTransport;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.UUID;

/**
 * Simple standalone client to test the RSocket/TCP endpoints
 * exposed by {@link TradingStateService}.
 * <p>
 * Run the {@code main} method while the trading-state-service is up
 * (docker compose up). It connects to {@code localhost:7000} and exercises
 * each RSocket route.
 */
public class RSocketTestClient {

    private static final String HOST = "localhost";
    private static final int PORT = 7000;

    public static void main(String[] args) throws InterruptedException {

        // Build strategies with Jackson JSON codecs
        RSocketStrategies strategies = RSocketStrategies.builder()
                .encoder(new JacksonJsonEncoder())
                .decoder(new JacksonJsonDecoder())
                .build();

        // Build a requester that connects over TCP
        RSocketRequester requester = RSocketRequester.builder()
                .rsocketStrategies(strategies)
                .transport(TcpClientTransport.create(HOST, PORT));

        System.out.println("=== Connected to RSocket server at " + HOST + ":" + PORT + " ===\n");

        // -------------------------------------------------------
        // 1. Submit a fill  (request-response  →  state.fills)
        // -------------------------------------------------------
        Fill fill = new Fill(
                UUID.randomUUID(),
                "AAPL",
                Side.BUY,
                100,
                150.25,
                UUID.randomUUID(),
                System.currentTimeMillis()
        );

        System.out.println(">>> Submitting fill: " + fill);
        requester.route("state.fills")
                .data(fill)
                .retrieveMono(Void.class)
                .doOnSuccess(v -> System.out.println("<<< Fill submitted successfully\n"))
                .doOnError(e -> System.err.println("<<< Fill error: " + e.getMessage()))
                .block(Duration.ofSeconds(5));

        // -------------------------------------------------------
        // 2. Get a single position  (request-response  →  positions.AAPL)
        // -------------------------------------------------------
        System.out.println(">>> Getting position for AAPL");
        Position position = requester.route("positions.AAPL")
                .retrieveMono(Position.class)
                .doOnError(e -> System.err.println("<<< Position error: " + e.getMessage()))
                .block(Duration.ofSeconds(5));
        System.out.println("<<< Position: " + position + "\n");

        // -------------------------------------------------------
        // 3. Get all positions  (request-stream  →  positions)
        // -------------------------------------------------------
        System.out.println(">>> Getting all positions");
        requester.route("positions")
                .retrieveFlux(Position.class)
                .doOnNext(p -> System.out.println("    " + p))
                .doOnError(e -> System.err.println("<<< All-positions error: " + e.getMessage()))
                .blockLast(Duration.ofSeconds(5));
        System.out.println();

        // -------------------------------------------------------
        // 4. Stream live updates  (request-stream  →  state.stream)
        //    A background thread submits fills every 2 seconds
        //    while the main thread listens to the stream for 15 s.
        // -------------------------------------------------------
        System.out.println(">>> Subscribing to state.stream (will listen for 15 s) ...");
        Disposable subscription = requester.route("state.stream")
                .retrieveFlux(StateSnapshot.class)
                .doOnNext(snap -> System.out.println("    Live update: " + snap))
                .doOnError(e -> System.err.println("<<< Stream error: " + e.getMessage()))
                .subscribe();

        // Background thread that submits fills while the stream is open
        Thread fillSubmitter = new Thread(() -> {
            String[] symbols = {"AAPL", "GOOG", "MSFT", "AAPL", "GOOG"};
            Side[] sides = {Side.SELL, Side.BUY, Side.BUY, Side.BUY, Side.SELL};
            int[] quantities = {50, 200, 75, 30, 100};
            double[] prices = {151.00, 2800.50, 330.10, 149.75, 2810.00};

            for (int i = 0; i < symbols.length; i++) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Fill f = new Fill(
                        UUID.randomUUID(),
                        symbols[i],
                        sides[i],
                        quantities[i],
                        prices[i],
                        UUID.randomUUID(),
                        System.currentTimeMillis()
                );
                System.out.println("\n>>> [fill-thread] Submitting fill #" + (i + 1) + ": " + f);
                requester.route("state.fills")
                        .data(f)
                        .retrieveMono(Void.class)
                        .doOnSuccess(v -> System.out.println("<<< [fill-thread] Fill submitted"))
                        .doOnError(e -> System.err.println("<<< [fill-thread] Error: " + e.getMessage()))
                        .block(Duration.ofSeconds(5));
            }
            System.out.println("\n>>> [fill-thread] Done submitting fills");
        }, "fill-submitter");

        fillSubmitter.start();
        fillSubmitter.join(); // wait for all fills to be submitted
        Thread.sleep(3000);   // linger a bit to catch any final stream events
        subscription.dispose();

        System.out.println("\n=== Done ===");
        requester.dispose();
    }
}


package edu.yu.velocitytrading.cluster;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.yu.velocitytrading.marketmaker.MarketMaker;
import edu.yu.velocitytrading.model.StateSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP server that accepts leader-forwarded {@link StateSnapshot}s on every
 * node. The wire format is newline-delimited JSON: one serialized
 * {@code StateSnapshot} per line.
 *
 * Fire-and-forget: the receiver never responds and never NACKs. Malformed
 * lines are logged and skipped so a single bad frame can't wedge the stream.
 * The leader drops its own inbound connections because it never gets
 * assignments (empty list), so this component is effectively worker-only at
 * runtime even though it runs on every node for simplicity.
 */
@Component
@Profile("market-maker-node")
public class WorkerForwardReceiver {

    private static final Logger log = LoggerFactory.getLogger(WorkerForwardReceiver.class);

    private final int port;
    private final MarketMaker marketMaker;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService acceptor;
    private ExecutorService handlers;

    public WorkerForwardReceiver(ClusterProperties props, MarketMaker marketMaker) {
        this.port = props.getForwardPort();
        this.marketMaker = marketMaker;
    }

    @PostConstruct
    public void start() throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.acceptor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "forward-receiver-accept");
            t.setDaemon(true);
            return t;
        });
        this.handlers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "forward-receiver-handler");
            t.setDaemon(true);
            return t;
        });
        running.set(true);
        acceptor.submit(this::acceptLoop);
        log.info("forward receiver listening on port {}", port);
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket s = serverSocket.accept();
                handlers.submit(() -> handle(s));
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("accept failed: {}", e.toString());
                }
            }
        }
    }

    private void handle(Socket s) {
        String remote = s.getRemoteSocketAddress().toString();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    StateSnapshot snap = mapper.readValue(line, StateSnapshot.class);
                    marketMaker.handleForwardedSnapshot(snap);
                } catch (Exception parseError) {
                    log.warn("dropping malformed forward frame from {}: {}", remote, parseError.toString());
                }
            }
        } catch (IOException e) {
            log.debug("connection from {} closed: {}", remote, e.toString());
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        if (acceptor != null) acceptor.shutdownNow();
        if (handlers != null) handlers.shutdownNow();
    }
}

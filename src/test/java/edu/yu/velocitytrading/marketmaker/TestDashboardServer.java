package edu.yu.velocitytrading.marketmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.yu.velocitytrading.model.ExposureState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TestDashboardServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_EVENTS = 300;

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<DashboardEvent> events = new ArrayList<>();

    private String overallStatus = "IDLE";
    private String currentStep = "Not started";
    private String message = "Waiting...";
    private Integer bidUsage = 0;
    private Integer askUsage = 0;
    private Integer totalCapacity = 100;
    private Integer activeReservations = 0;

    private TestDashboardServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static TestDashboardServer start(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        TestDashboardServer dashboard = new TestDashboardServer(httpServer, executor);

        httpServer.createContext("/", dashboard::handleRoot);
        httpServer.createContext("/state", dashboard::handleState);
        httpServer.setExecutor(executor);
        httpServer.start();

        return dashboard;
    }

    public synchronized void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public synchronized void mark(String step, String status, String msg, ExposureState exposure) {
        this.currentStep = step;
        this.overallStatus = status;
        this.message = msg;

        if (exposure != null) {
            this.bidUsage = exposure.bidUsage();
            this.askUsage = exposure.askUsage();
            this.totalCapacity = exposure.totalCapacity();
            this.activeReservations = exposure.activeReservations();
        }

        events.add(new DashboardEvent(
                Instant.now().toString(),
                step,
                status,
                msg,
                this.bidUsage,
                this.askUsage,
                this.totalCapacity,
                this.activeReservations));

        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        byte[] body = loadDashboardHtml();
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private synchronized void handleState(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        DashboardState state = new DashboardState(
                overallStatus,
                currentStep,
                message,
                bidUsage,
                askUsage,
                totalCapacity,
                totalCapacity - bidUsage,
                totalCapacity - askUsage,
                (totalCapacity * 2) - bidUsage - askUsage,
                activeReservations,
                new ArrayList<>(events));

        byte[] json = MAPPER.writeValueAsBytes(state);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, json.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    private byte[] loadDashboardHtml() throws IOException {
        try (InputStream is = TestDashboardServer.class.getResourceAsStream("/dashboard.html")) {
            if (is == null) {
                String fallback = "<html><body><h1>dashboard.html not found</h1></body></html>";
                return fallback.getBytes(StandardCharsets.UTF_8);
            }
            return is.readAllBytes();
        }
    }

    public record DashboardEvent(
            String timestamp,
            String step,
            String status,
            String message,
            Integer bidUsage,
            Integer askUsage,
            Integer totalCapacity,
            Integer activeReservations) {
    }

    public record DashboardState(
            String overallStatus,
            String currentStep,
            String message,
            Integer bidUsage,
            Integer askUsage,
            Integer totalCapacity,
            Integer bidAvailable,
            Integer askAvailable,
            Integer totalReservationAvailable,
            Integer activeReservations,
            List<DashboardEvent> events) {
    }
}


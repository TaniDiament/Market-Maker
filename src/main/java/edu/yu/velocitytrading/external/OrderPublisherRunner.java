package edu.yu.velocitytrading.external;

import java.io.IOException;

public class OrderPublisherRunner {

    public static void main(String[] args) {
        try {
            ExternalOrderPublisher publisher = new ExternalOrderPublisher("http://localhost:8080");
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {publisher.stop();})
            );
            if (shouldInitialize(args)) {
                publisher.sendInitialQuotes();
            }
            publisher.startGeneratingOrders();
        } catch (IOException e) {
            System.out.println("Error");
        }
    }

    private static boolean shouldInitialize(String[] args) {
        return args.length == 0 || !args[0].equals("--continue");
    }
}

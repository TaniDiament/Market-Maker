package edu.yu.velocitytrading.external;

/**
 * A counter to track successful events
 */
public class Counter {
    
    private int total;
    private int successful;

    public Counter() {
        this.total = 0;
        this.successful = 0;
    }

    public synchronized void increment(boolean isSuccessful) {
        this.total++;
        if (isSuccessful) {
            this.successful++;
        }
    }

    public double getPercentage() {
        return ((double) successful) / total;
    }
}

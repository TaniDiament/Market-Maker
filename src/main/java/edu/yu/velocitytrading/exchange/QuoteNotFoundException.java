package edu.yu.velocitytrading.exchange;

public class QuoteNotFoundException extends RuntimeException {
    
    public QuoteNotFoundException(String symbol) {
        super("Could not find quote with symbol " + symbol);
    }
}

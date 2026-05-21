package edu.yu.velocitytrading.marketmaker;

import edu.yu.velocitytrading.model.Fill;
import edu.yu.velocitytrading.model.Position;
import edu.yu.velocitytrading.model.Quote;

public interface QuoteGenerator {
    
    public Quote generateQuote(Position position, Fill lastFill);
}

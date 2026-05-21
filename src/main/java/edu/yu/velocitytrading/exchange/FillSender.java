package edu.yu.velocitytrading.exchange;

import edu.yu.velocitytrading.model.Fill;

/**
 * Interface for a component that handles sending fills
 */
public interface FillSender {
    
    /**
     * Handle the sending of a fill
     * @param fill the fill to send
     */
    public void sendFill(Fill fill);
}

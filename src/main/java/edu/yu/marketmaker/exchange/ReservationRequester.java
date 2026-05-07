package edu.yu.marketmaker.exchange;

import edu.yu.marketmaker.ha.LeaderAwareRSocketClient;
import edu.yu.marketmaker.model.Quote;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("exchange")
public class ReservationRequester {

    private final LeaderAwareRSocketClient client;

    public ReservationRequester(LeaderAwareRSocketClient client) {
        this.client = client;
    }

    public void sendReservation(Quote quote) {
        client.send("exposure-reservation", "reservations", quote);
    }
}
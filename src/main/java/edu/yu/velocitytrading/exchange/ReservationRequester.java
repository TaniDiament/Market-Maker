package edu.yu.velocitytrading.exchange;

import edu.yu.velocitytrading.ha.LeaderAwareRSocketClient;
import edu.yu.velocitytrading.model.Quote;
import edu.yu.velocitytrading.model.ReservationResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("exchange")
public class ReservationRequester {

    private final LeaderAwareRSocketClient client;

    public ReservationRequester(LeaderAwareRSocketClient client) {
        this.client = client;
    }

    /**
     * Bootstrap a reservation for {@code quote} on the exposure-reservation
     * leader. Synchronous request/response so the caller can be sure the
     * reservation is committed before {@code PUT /quotes} returns — without
     * that ordering, a follow-on order race-arrives at {@code apply-fill}
     * and the handler aborts with "Reservation not found".
     *
     * <p>Implementation note: must be request/response, not fire-and-forget.
     * The receiving handler ({@code @MessageMapping("reservations")} on
     * {@code ExposureReservationAPI}) is registered as a request/response
     * route because it returns {@link ReservationResponse}; Spring's RSocket
     * dispatcher will silently drop a fire-and-forget frame to that route
     * with "No handler for fireAndForget to 'reservations'" and the
     * reservation never gets created.
     */
    public void sendReservation(Quote quote) {
        client.requestResponse("exposure-reservation", "reservations", quote,
                        ReservationResponse.class)
                .block();
    }
}
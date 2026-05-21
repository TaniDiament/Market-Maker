package edu.yu.velocitytrading.exposurereservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.yu.velocitytrading.memory.Repository;
import edu.yu.velocitytrading.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-layer tests for ExposureReservationAPI.
 *
 * From components.md:
 *   POST /reservations            → request exposure for a proposed quote
 *   POST /reservations/{id}/apply-fill → update reservation after fill
 *   POST /reservations/{id}/release    → release reservation
 *   GET  /exposure                → read current global exposure usage
 *   GET  /health                  → health check
 *
 * Observable requirements:
 *   - A reservation is either granted, reduced, or rejected
 *   - Retrying the same reservation must not double-reserve
 *   - Exposure for expired/replaced quotes is eventually released
 */
@WebMvcTest(ExposureReservationAPI.class)
@ActiveProfiles("exposure-reservation")
class ExposureReservationAPITest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private Repository<String, Reservation> reservationRepository;

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Exposure Reservation Service"));
    }

    // POST /reservations

    @Test
    void createReservationReturnsGranted() throws Exception {
        when(reservationRepository.getAll()).thenReturn(Collections.emptyList());

        Quote quote = makeQuote();

        mockMvc.perform(post("/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRANTED"))
                .andExpect(jsonPath("$.grantedBidQuantity").value(10))
                .andExpect(jsonPath("$.grantedAskQuantity").value(10))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void createReservationReturnsPartial() throws Exception {
        Reservation existing = new Reservation("GOOG", "GOOG", 95, 95, 95, 95, ReservationStatus.GRANTED);
        when(reservationRepository.getAll()).thenReturn(List.of(existing));

        Quote quote = makeQuote();

        mockMvc.perform(post("/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.grantedBidQuantity").value(5))
                .andExpect(jsonPath("$.grantedAskQuantity").value(5));
    }

    @Test
    void createReservationReturnsDenied() throws Exception {
        Reservation existing = new Reservation("GOOG", "GOOG", 100, 100, 100, 100, ReservationStatus.GRANTED);
        when(reservationRepository.getAll()).thenReturn(List.of(existing));

        Quote quote = makeQuote();

        mockMvc.perform(post("/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.grantedBidQuantity").value(0))
                .andExpect(jsonPath("$.grantedAskQuantity").value(0));
    }

    // POST /reservations/{id}/apply-fill

    @Test
    void applyFillReturnsFreedCapacity() throws Exception {
        String symbol = "AAPL";
        Reservation reservation = new Reservation(symbol, symbol, 40, 40, 40, 40, ReservationStatus.GRANTED);
        when(reservationRepository.get(symbol)).thenReturn(Optional.of(reservation));

        Fill fill = new Fill(UUID.randomUUID(), symbol, Side.BUY, 10, 100.0, UUID.randomUUID(), System.currentTimeMillis());

        mockMvc.perform(post("/reservations/" + symbol + "/apply-fill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freedCapacity").value(10));
    }

    @Test
    void applyFillFullConsumption() throws Exception {
        String symbol = "AAPL";
        Reservation reservation = new Reservation(symbol, symbol, 20, 20, 20, 20, ReservationStatus.GRANTED);
        when(reservationRepository.get(symbol)).thenReturn(Optional.of(reservation));

        Fill fill = new Fill(UUID.randomUUID(), symbol, Side.BUY, 20, 100.0, UUID.randomUUID(), System.currentTimeMillis());

        mockMvc.perform(post("/reservations/" + symbol + "/apply-fill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freedCapacity").value(20));
    }

    // POST /reservations/{id}/release

    @Test
    void releaseReturnsFreedCapacity() throws Exception {
        String symbol = "AAPL";
        Reservation reservation = new Reservation(symbol, symbol, 30, 30, 30, 30, ReservationStatus.GRANTED);
        when(reservationRepository.get(symbol)).thenReturn(Optional.of(reservation));

        mockMvc.perform(post("/reservations/" + symbol + "/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freedCapacity").value(60));
    }

    @Test
    void releasePartiallyFilledReservation() throws Exception {
        String symbol = "AAPL";
        // Was 30/30, already filled 10 on each side, so remaining granted is 20/20.
        Reservation reservation = new Reservation(symbol, symbol, 30, 20, 30, 20, ReservationStatus.GRANTED);
        when(reservationRepository.get(symbol)).thenReturn(Optional.of(reservation));

        mockMvc.perform(post("/reservations/" + symbol + "/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freedCapacity").value(40));
    }

    // GET /exposure

    @Test
    void getExposureShowsUsageAndCapacity() throws Exception {
        List<Reservation> reservations = List.of(
                new Reservation("AAPL", "AAPL", 20, 20, 20, 20, ReservationStatus.GRANTED),
                new Reservation("GOOG", "GOOG", 30, 15, 30, 15, ReservationStatus.PARTIAL)
        );
        when(reservationRepository.getAll()).thenReturn(reservations);

        mockMvc.perform(get("/exposure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidUsage").value(35))
                .andExpect(jsonPath("$.askUsage").value(35))
                .andExpect(jsonPath("$.totalCapacity").value(100))
                .andExpect(jsonPath("$.activeReservations").value(2));
    }

    @Test
    void getExposureShowsZeroWhenEmpty() throws Exception {
        when(reservationRepository.getAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/exposure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidUsage").value(0))
                .andExpect(jsonPath("$.askUsage").value(0))
                .andExpect(jsonPath("$.totalCapacity").value(100))
                .andExpect(jsonPath("$.activeReservations").value(0));
    }

    @Test
    void getExposureExcludesReleasedReservations() throws Exception {
        List<Reservation> reservations = List.of(
                new Reservation("AAPL", "AAPL", 50, 0, 50, 0, ReservationStatus.GRANTED),  // released
                new Reservation("GOOG", "GOOG", 30, 30, 30, 30, ReservationStatus.GRANTED)  // active
        );
        when(reservationRepository.getAll()).thenReturn(reservations);

        mockMvc.perform(get("/exposure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidUsage").value(30))
                .andExpect(jsonPath("$.askUsage").value(30))
                .andExpect(jsonPath("$.activeReservations").value(1));
    }

    private Quote makeQuote() {
        return new Quote("AAPL", 99.0, 10, 101.0, 10, UUID.randomUUID(),
                System.currentTimeMillis() + 30_000);
    }
}
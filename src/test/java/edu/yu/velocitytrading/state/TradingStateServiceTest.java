package edu.yu.velocitytrading.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.yu.velocitytrading.memory.Repository;
import edu.yu.velocitytrading.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for TradingStateService
 *
 * From requirements.md:
 *   - Each symbol has a single net position
 *   - Positions are updated sequentially by applying recorded fills
 *   - Positive = long, negative = short
 *   - Position versions are monotonic per symbol
 *   - Fills are recorded durably before any quote replacement occurs
 *   - A fill is immutable once created
 *
 * From components.md:
 *   - Records fills durably and applies them exactly once
 *   - A fill is not considered reflected until durably recorded
 */
@WebMvcTest(TradingStateService.class)
@ActiveProfiles("trading-state")
class TradingStateServiceTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private Repository<String, Position> positionRepository;

    @MockitoBean
    private Repository<UUID, Fill> fillRepository;

    @MockitoBean
private edu.yu.velocitytrading.ha.LeaderElectionService leaderElection;

@org.junit.jupiter.api.BeforeEach
void assumeLeader() {
    org.mockito.Mockito.when(leaderElection.isLeader()).thenReturn(true);
}

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trading State Service"));
    }

    // Position creation, Initial position: 0

    @Test
    void buyFillCreatesPositionFromZero() throws Exception {
        when(positionRepository.get("AAPL")).thenReturn(Optional.empty());

        Fill fill = makeFill("AAPL", Side.BUY, 10);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos ->
                pos.symbol().equals("AAPL") && pos.netQuantity() == 10 && pos.version() == 0
        ));
    }

    @Test
    void sellFillCreatesShortPositionFromZero() throws Exception {
        // "A negative position represents a short position.
        when(positionRepository.get("AAPL")).thenReturn(Optional.empty());

        Fill fill = makeFill("AAPL", Side.SELL, 10);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos ->
                pos.symbol().equals("AAPL") && pos.netQuantity() == -10 && pos.version() == 0
        ));
    }

    // Position arithmetic, Buy fill of 3 -> position = +3, Sell fill of 5 -> position = -2

    @Test
    void buyFillIncreasesPosition() throws Exception {
        Position existing = new Position("AAPL", 20, 3, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(existing));

        Fill fill = makeFill("AAPL", Side.BUY, 5);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.netQuantity() == 25));
    }

    @Test
    void sellFillDecreasesPosition() throws Exception {
        Position existing = new Position("AAPL", 20, 3, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(existing));

        Fill fill = makeFill("AAPL", Side.SELL, 8);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.netQuantity() == 12));
    }

    @Test
    void positionGoesNegativeOnLargeSell() throws Exception {
        // +5 then sell 15 → -10
        Position existing = new Position("AAPL", 5, 1, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(existing));

        Fill fill = makeFill("AAPL", Side.SELL, 15);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.netQuantity() == -10));
    }

    @Test
    void buyFillReducesShortPosition() throws Exception {
        // Short -30, buy 10 → -20
        Position existing = new Position("AAPL", -30, 5, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(existing));

        Fill fill = makeFill("AAPL", Side.BUY, 10);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.netQuantity() == -20));
    }

    @Test
    void buyFillFlipsShortToLong() throws Exception {
        // Short -5, buy 20 → +15
        Position existing = new Position("AAPL", -5, 2, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(existing));

        Fill fill = makeFill("AAPL", Side.BUY, 20);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.netQuantity() == 15));
    }

    // Version monotonicity, position versions are monotonic per symbol

    @Test
    void newPositionStartsAtVersionZero() throws Exception {
        when(positionRepository.get("AAPL")).thenReturn(Optional.empty());

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(makeFill("AAPL", Side.BUY, 5))))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.version() == 0));
    }

    @Test
    void versionIncrementsOnEachFill() throws Exception {
        Position existing = new Position("AAPL", 10, 7, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(makeFill("AAPL", Side.BUY, 1))))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.version() == 8));
    }

    // Fill linkage, last_fill_id (or equivalent linkage)

    @Test
    void positionReferencesLastFillId() throws Exception {
        Position existing = new Position("AAPL", 10, 0, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(existing));

        UUID fillId = UUID.randomUUID();
        Fill fill = new Fill(fillId, "AAPL", Side.BUY, 5, 150.0, UUID.randomUUID(), System.currentTimeMillis());

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.lastFillId().equals(fillId)));
    }

    // Durability, Records fills durably and applies them exactly once
    @Test
    void fillIsPersistedBeforePositionUpdate() throws Exception {
        when(positionRepository.get("AAPL")).thenReturn(Optional.empty());

        Fill fill = makeFill("AAPL", Side.BUY, 5);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        InOrder inOrder = inOrder(fillRepository, positionRepository);
        inOrder.verify(fillRepository).put(any(Fill.class));
        inOrder.verify(positionRepository).put(any(Position.class));
    }

    // Independent symbols, positions are per symbol

    @Test
    void fillOnlyAffectsItsOwnSymbol() throws Exception {
        when(positionRepository.get("AAPL")).thenReturn(Optional.empty());

        Fill fill = makeFill("AAPL", Side.BUY, 10);

        mockMvc.perform(post("/state/fills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fill)))
                .andExpect(status().isOk());

        verify(positionRepository).put(argThat(pos -> pos.symbol().equals("AAPL")));
        verify(positionRepository, never()).put(argThat(pos -> !pos.symbol().equals("AAPL")));
    }

    // GET /positions, read positions

    @Test
    void getPositionsReturnsAll() throws Exception {
        List<Position> positions = List.of(
                new Position("AAPL", 10, 1, UUID.randomUUID()),
                new Position("GOOG", -5, 2, UUID.randomUUID())
        );
        when(positionRepository.getAll()).thenReturn(positions);

        mockMvc.perform(get("/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getPositionsReturnsEmptyWhenNone() throws Exception {
        when(positionRepository.getAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getPositionBySymbolShowsCorrectData() throws Exception {
        Position position = new Position("AAPL", -25, 5, UUID.randomUUID());
        when(positionRepository.get("AAPL")).thenReturn(Optional.of(position));

        mockMvc.perform(get("/positions/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.netQuantity").value(-25))
                .andExpect(jsonPath("$.version").value(5));
    }

    private Fill makeFill(String symbol, Side side, int quantity) {
        return new Fill(UUID.randomUUID(), symbol, side, quantity, 150.0,
                UUID.randomUUID(), System.currentTimeMillis());
    }
}
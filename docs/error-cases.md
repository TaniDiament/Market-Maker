# Error Cases

Error cases are scenarios where a service or component goes down or fails unexpectedly. Client-side behavior (expired quotes, bad prices, partial fills, denied reservations) is documented in the workflow cases.

---

## Submitting External Orders

### Error Case 1: Exchange goes down before handling the order
```mermaid
sequenceDiagram
  participant user as External Order Publisher
  participant exchange as Exchange Service
  user->>exchange: Submit order (POST /orders)
  exchange--xexchange: Crashes before processing
  Note over user: Connection error / timeout
  Note over user: Order is lost — no fill was generated
  Note over user: Publisher retries with new order
  Note over user: No state corruption since nothing was written
```
**Outcome:** The order is simply lost. No fill was created, no position changed, and no exposure was affected. The external order publisher can retry by submitting a new order. Since the order never reached the matching engine, there is no risk of duplicate fills or inconsistent state.

---

### Error Case 2: Exchange goes down after sending the fill but before confirming to the publisher
```mermaid
sequenceDiagram
  participant user as External Order Publisher
  participant exchange as Exchange Service
  participant state as Trading State Service
  user->>exchange: Submit order (POST /orders)
  exchange->>exchange: Match and generate fill
  exchange->>state: Send fill (RSocket state.fills)
  state->>state: Record fill durably
  state->>state: Update position
  exchange--xexchange: Crashes before responding
  Note over user: Connection error / timeout
  Note over user: Publisher does not know if order succeeded
  Note over user: Fill was already recorded — position is correct
  Note over user: Publisher may retry, but new order gets a new UUID
  Note over user: so it cannot cause a duplicate fill
```
**Outcome:** The fill was already sent to and recorded by the trading state service, so the position is correct. The publisher sees a timeout and doesn't know if the order succeeded. If it retries, the new order has a new UUID and is treated as a separate order — no duplication. The quote's remaining quantity was already decremented before the crash.

---

### Error Case 3: Trading state service goes down before handling the fill
```mermaid
sequenceDiagram
  participant user as External Order Publisher
  participant exchange as Exchange Service
  participant state as Trading State Service
  user->>exchange: Submit order (POST /orders)
  exchange->>exchange: Match and generate fill
  exchange->>state: Send fill (RSocket state.fills)
  state--xstate: Crashes before recording fill
  Note over exchange: RSocket send is fire-and-forget
  Note over exchange: Exchange does not know fill was lost
  exchange->>user: Report success
  Note over state: Fill is lost — position NOT updated
  Note over state: Quote quantity was already decremented
  Note over state: Position and quote are now inconsistent
  Note over state: On restart, trading state has no record of fill
```
**Outcome:** This is a critical inconsistency. The exchange already decremented the quote's remaining quantity, but the fill was never recorded. The position does not reflect the trade. Since RSocket fire-and-forget provides no delivery guarantee, the fill is lost. The quote will eventually expire (30s TTL), releasing its exposure, and the market maker will publish a new quote based on the (stale) position. This represents a known gap — a more robust design would use at-least-once delivery with idempotent fill recording.

---

## Updating Quote

### Error Case 4: Market maker goes down before handling the position update
```mermaid
sequenceDiagram
  participant state as Trading State Service
  participant maker as Market Maker Node
  state->>maker: Position update (RSocket state.stream)
  maker--xmaker: Crashes before processing
  Note over maker: Position update is lost for this node
  Note over maker: Existing quote for symbol remains active
  Note over maker: Quote will eventually expire (30s TTL)
  Note over maker: On restart, market maker reconnects to state.stream
  Note over maker: Receives current positions as initial snapshot
  Note over maker: Generates and publishes fresh quotes
```
**Outcome:** The position update is lost for this market maker instance. The currently active quote (if any) continues until its TTL expires. When the market maker restarts, it reconnects to `state.stream`, receives the full current position snapshot, and resumes publishing quotes based on the latest committed state.

---

### Error Case 5: Market maker goes down after sending reservation but before sending new quote
```mermaid
sequenceDiagram
  participant state as Trading State Service
  participant maker as Market Maker Node
  participant reservation as Exposure Reservation Service
  participant exchange as Exchange Service
  state->>maker: Position update
  maker->>maker: Generate new quote
  maker->>reservation: Request reservation (RSocket "reservations")
  reservation->>reservation: Grant reservation (exposure reserved)
  reservation->>maker: Reservation granted (keyed by symbol)
  maker--xmaker: Crashes before publishing quote
  Note over reservation: Exposure is reserved but no quote is active
  Note over reservation: Orphan counts against the global budget
  Note over reservation: Reservations are keyed by symbol (one per symbol)
  Note over reservation: Next createReservation for this symbol
  Note over reservation: atomically releases the orphan before regranting
  Note over exchange: Old quote remains (or expires via TTL)
```
**Outcome:** Exposure is reserved but no quote is live, so capacity is briefly leaked — and because usage is summed globally across all symbols, the orphan reduces the budget available to other symbols until it is reclaimed. The leak is **bounded and self-healing**, not permanent. Reservations are keyed by symbol (a reservation's `id` *is* its symbol), so there is at most one per symbol, and `ExposureReservationService.createReservation` releases any existing reservation for that symbol before granting a new one; the whole method is `synchronized`, so the release-and-regrant is atomic. The next quote cycle for the symbol therefore reclaims the orphan automatically: on restart the market maker reconnects to `state.stream`, receives the position snapshot, and regenerates quotes (the Error Case 4 path); even without a restart, `QuoteFreshnessKeeper` periodically refreshes every MM-owned symbol — including symbols whose quote is missing or expired — issuing a fresh reservation that supersedes the orphan. There is no reservation TTL: recovery is purely supersede-driven, so the leak window is bounded by the next quote refresh rather than by an expiry timer.

---

### Error Case 6: Reservation service goes down before updating reservation
```mermaid
sequenceDiagram
  participant state as Trading State Service
  participant maker as Market Maker Node
  participant reservation as Exposure Reservation Service
  state->>maker: Position update
  maker->>maker: Generate new quote
  maker->>reservation: Request reservation (RSocket "reservations")
  reservation--xreservation: Crashes before processing
  Note over maker: Connection error / timeout
  Note over maker: No reservation was granted
  Note over maker: Market maker must NOT publish quote
  Note over maker: Old quote remains until TTL expires
  Note over maker: Market maker retries on next position update
```
**Outcome:** The market maker receives an error when trying to reserve exposure. Per the authority boundaries, a quote must not become active without a granted reservation. The market maker does not publish the quote. The old quote (if any) remains until it expires. On the next position update or refresh cycle, the market maker retries.

---

### Error Case 7: Exchange service goes down before updating quote
```mermaid
sequenceDiagram
  participant state as Trading State Service
  participant maker as Market Maker Node
  participant reservation as Exposure Reservation Service
  participant grid as Shared Hazelcast quotes map
  participant exchange as Exchange Service
  state->>maker: Position update
  maker->>maker: Generate new quote
  maker->>reservation: Request reservation (RSocket "reservations")
  reservation->>maker: Reservation granted (keyed by symbol)
  maker->>grid: Publish quote (quoteRepository.put → IMap + write-through MapStore)
  exchange--xexchange: Exchange pod is down
  Note over grid: Quote is committed to the grid + postgres
  Note over grid: independent of exchange liveness
  Note over reservation: Reservation is backed by a live quote — no leak
  Note over exchange: While down it matches no orders
  Note over exchange: On restart it reads the durable quote from the grid
```
**Outcome:** In production the market maker does **not** publish quotes by calling the exchange over HTTP. It writes the reserved quote to the shared Hazelcast `quotes` map (`ProductionQuoteGenerator` → `quoteRepository.put`), which is replicated across the cluster and write-through-persisted to PostgreSQL. The exchange reads quotes from that same grid. So an exchange pod going down does not lose the quote or orphan the reservation — both the quote and its reservation are committed. While the exchange leader is down no new orders are matched (clients fail over to the ZooKeeper-re-elected leader), and when the exchange returns it serves the already-durable quote. The `PUT /quotes/{symbol}` endpoint on the exchange (`ExchangeService.putQuote`) exists only for initial bootstrap — it no-ops when a quote already exists — not for steady-state publication.

---

## Streaming Position Data Updates

### Error Case 8: Connected trading state service goes down
```mermaid
sequenceDiagram
  participant frontend as Position Display UI (browser)
  participant state as Trading State Service
  frontend->>state: Connect (STOMP over SockJS WebSocket /ws)
  state->>frontend: Live position deltas (/topic/positions)
  state--xstate: Crashes
  Note over frontend: WebSocket connection broken
  Note over frontend: UI shows last known positions (stale)
  Note over frontend: UI should display disconnected indicator
  Note over frontend: UI retries connection with backoff
  state->>state: Restarts, rebuilds positions from DB
  frontend->>state: Reconnect (SockJS /ws), SUBSCRIBE /topic/positions
  state->>frontend: Initial snapshot via /app/positions.snapshot
  Note over frontend: UI now shows current positions again
```
**Outcome:** The browser UI talks to trading-state over **STOMP on a SockJS WebSocket** (`/ws`), not RSocket. When the connection breaks it shows stale data, should surface a disconnected indicator, and retries with backoff. When the trading state service restarts, it rebuilds positions from PostgreSQL via the Hazelcast MapStore; the UI reconnects, fetches an initial snapshot via `/app/positions.snapshot`, and resumes live deltas on `/topic/positions`. (Market-maker pods consume the same multicast `StateSnapshot` stream over a separate RSocket `state.stream` request-stream — that transport is covered by Error Cases 4–5.)

---

## Exposure Lifecycle Errors

### Error Case 9: Fill arrives but reservation apply-fill fails
```mermaid
sequenceDiagram
  participant user as External Order Publisher
  participant exchange as Exchange Service
  participant reservation as Exposure Reservation Service
  participant state as Trading State Service
  user->>exchange: Submit order (POST /orders)
  exchange->>exchange: Validate quote, compute fill quantity
  exchange->>reservation: apply-fill (RSocket "reservations.{symbol}.apply-fill")
  reservation--xreservation: Fails or times out
  Note over exchange: apply-fill is the FIRST mutating step
  Note over exchange: Quote not decremented, no fill generated
  exchange->>user: Reject order (OrderValidationException → HTTP 400)
  Note over state: No fill sent — position unchanged
  Note over reservation: Capacity unchanged — nothing was applied
```
**Outcome:** In production the **exchange**, not the market maker, applies fills to reservations. `FillOrderDispatcher.dispatchOrder` calls `reservations.{symbol}.apply-fill` (RSocket request-response, keyed by symbol) as the **first** mutating step — before it decrements the quote or sends the fill to trading-state. If that call fails or returns no response, it throws `OrderValidationException` (mapped to HTTP 400 for the publisher): the quote is left untouched, no fill is generated, and trading-state never sees a fill. So there is no over-reservation and no position/quote drift — the order simply fails and the publisher may retry. (The market maker plays no part in apply-fill.)

---

### Error Case 10: Market maker crashes during quote replacement cycle
```mermaid
sequenceDiagram
  participant state as Trading State Service
  participant maker as Market Maker Node
  participant reservation as Exposure Reservation Service
  state->>maker: Position update (new fill processed)
  Note over maker: Production does NOT release first —
  Note over maker: createReservation atomically supersedes the
  Note over maker: symbol's prior reservation (one per symbol)
  maker->>reservation: Request new reservation (RSocket "reservations")
  reservation->>reservation: Release prior grant + regrant (synchronized)
  reservation->>maker: New reservation granted
  maker->>maker: Publish reserved quote to shared Hazelcast map
  Note over maker: A mid-cycle crash collapses to Error Case 4 or 5 —
  Note over maker: never "active quote with no reservation"
```
**Outcome:** The "release the old reservation, then request a new one" sequence does not exist in production. `ProductionQuoteGenerator` requests the new reservation over the RSocket `reservations` route, and `ExposureReservationService.createReservation` releases the symbol's prior reservation and regrants in a single `synchronized` call (there is exactly one reservation per symbol, keyed by symbol). A crash during the replacement cycle therefore collapses into an already-covered case: before the reservation call → **Error Case 4** (old quote and reservation intact); after the grant but before the quote is published → **Error Case 5** (self-healing orphan). The "active quote with no backing reservation" window is only reachable through the fault-injection harness (`ProductionQuoteGenerator.maybeTriggerError10Crash`), which deliberately calls `reservations.{symbol}.release` and then `Runtime.halt` to reproduce the scenario for `ClusterError10…Test`; even then it is bounded by the quote's 30s TTL.

---

## Full System Restart

### Error Case 11: Recovery after full system restart
```mermaid
sequenceDiagram
  participant pg as PostgreSQL
  participant state as Trading State Service
  participant reservation as Exposure Reservation Service
  participant exchange as Exchange Service
  participant maker as Market Maker Node
  Note over pg: PostgreSQL starts (data is durable)
  state->>pg: Start — Hazelcast MapStore eager-loads positions, fills
  reservation->>pg: Start — Hazelcast MapStore eager-loads reservations
  exchange->>pg: Start — Hazelcast MapStore eager-loads quotes (TTL may have lapsed during downtime)
  maker->>state: Connect to state.stream
  state->>maker: Initial snapshot (current Position + lastFill per symbol)
  Note over maker: AssignmentListener.bootstrapQuoteForNewlyAssigned: skip if a non-expired durable quote already exists, otherwise regen.
  maker->>maker: ProductionQuoteGenerator: expired survivor → treated as null → cold-start defaults (defaultQuantity, referencePrice=100)
  maker->>reservation: Request fresh reservation (atomically supersedes any pre-restart entry for this symbol)
  reservation->>maker: Grant / partial / deny
  maker->>exchange: Publish new quote (write-through to postgres)
  Note over maker: System resumes accepting orders against the refreshed quotes
```
**Outcome:** Each service rebuilds from durable storage via `MapStoreConfig.InitialLoadMode.EAGER`. There is no separate startup pass to "scan and expire" stale entries — quote expiry is handled lazily on read (the exchange's `FillOrderDispatcher` rejects orders against expired quotes, and `ProductionQuoteGenerator` treats an expired survivor as if no quote existed when it regenerates). Reservation exposure totals are derived on every `createReservation` call by summing `Reservation.grantedBid/grantedAsk` across the loaded `reservations` IMap, so the global capacity is correct as soon as the MapStore finishes its eager load. Market makers reconnect to `state.stream`, receive the position snapshot (with `lastFill`, enabling inventory-aware skew on the first regen), and resume quoting. End-to-end coverage lives in `ClusterError11RecoveryAfterFullSystemRestartTest` / `LocalError11RecoveryAfterFullSystemRestartTest`; the cluster variant intentionally does **not** restart `sts/zk` or `sts/postgres` because those are the durable layer being relied on.

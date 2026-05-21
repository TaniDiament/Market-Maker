package edu.yu.velocitytrading.exposurereservation;

import edu.yu.velocitytrading.model.Reservation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;

@Profile("testing")
public class InMemoryReservationRepository implements ReservationRepository {
    private final Map<String, Reservation> store = new ConcurrentHashMap<>();

    @Override
    public void save(Reservation reservation) {
        store.put(reservation.id(), reservation);
    }

    @Override
    public Optional<Reservation> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Collection<Reservation> findAll() {
        return store.values();
    }
}

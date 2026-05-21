package edu.yu.velocitytrading.exposurereservation;

import edu.yu.velocitytrading.model.Reservation;

import java.util.Collection;
import java.util.Optional;

public interface ReservationRepository {
    void save(Reservation reservation);
    Optional<Reservation> findById(String id);
    Collection<Reservation> findAll();
}
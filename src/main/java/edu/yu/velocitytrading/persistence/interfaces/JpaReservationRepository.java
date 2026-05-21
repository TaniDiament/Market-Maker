package edu.yu.velocitytrading.persistence.interfaces;

import edu.yu.velocitytrading.persistence.BaseJpaRepository;
import edu.yu.velocitytrading.persistence.ReservationEntity;
import org.springframework.stereotype.Repository;

/**
 * JPA Repository interface for ReservationEntity persistence.
 * Spring Data JPA requires concrete interfaces to create proxy implementations.
 */
@Repository
public interface JpaReservationRepository extends BaseJpaRepository<ReservationEntity, String> {
}


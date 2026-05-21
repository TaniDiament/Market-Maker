package edu.yu.velocitytrading.persistence.interfaces;

import edu.yu.velocitytrading.persistence.BaseJpaRepository;
import edu.yu.velocitytrading.persistence.PositionEntity;
import org.springframework.stereotype.Repository;

/**
 * JPA Repository interface for PositionEntity persistence.
 * Spring Data JPA requires concrete interfaces to create proxy implementations.
 */
@Repository
public interface JpaPositionRepository extends BaseJpaRepository<PositionEntity, String> {
}


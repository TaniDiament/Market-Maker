package edu.yu.velocitytrading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base JPA repository interface for entities with identifiable keys.
 * This provides a common contract for all JPA repositories in the persistence layer.
 *
 * Note: Spring Data JPA requires concrete interfaces for each entity type,
 * so individual repositories must still extend this with specific type parameters.
 *
 * @param <E> the entity type
 * @param <K> the key/ID type
 */
@NoRepositoryBean
public interface BaseJpaRepository<E, K> extends JpaRepository<E, K> {
    // Inherits all standard CRUD methods from JpaRepository:
    // - save(E entity)
    // - saveAll(Iterable<E> entities)
    // - findById(K id)
    // - findAll()
    // - findAllById(Iterable<K> ids)
    // - deleteById(K id)
    // - delete(E entity)
    // - deleteAll()
    // - deleteAllById(Iterable<K> ids)
    // - count()
    // - existsById(K id)
}


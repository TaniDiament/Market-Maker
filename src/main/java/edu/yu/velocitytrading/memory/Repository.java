package edu.yu.velocitytrading.memory;

import edu.yu.velocitytrading.model.Identifiable;

import java.util.Collection;
import java.util.Optional;

/**
 * Generic repository interface for entities that implement Identifiable.
 *
 * @param <K> the key type
 * @param <T> the entity type, must implement Identifiable<K>
 */
public interface Repository<K, T extends Identifiable<K>> {
    /**
     * Get mapped value
     * @param id key of the entity
     * @return
     */
    Optional<T> get(K id);

    /**
     * Store entity
     * @param entity to be stored
     */
    void put(T entity);
    /**
     * Get all mapped values
     * @return
     */
    Collection<T> getAll();
    /**
     * Delete entity
     * @param id key of the entity
     */
    void delete(K id);
}

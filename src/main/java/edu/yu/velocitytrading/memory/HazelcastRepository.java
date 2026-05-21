package edu.yu.velocitytrading.memory;

import com.hazelcast.map.IMap;
import edu.yu.velocitytrading.model.Identifiable;

import java.util.Collection;
import java.util.Optional;

/**
 * Generic Hazelcast-backed implementation of Repository.
 * Uses a distributed IMap for storage with write-through
 * persistence to the database via the configured MapStore.
 *
 * @param <K> the key type
 * @param <T> the entity type, must implement Identifiable<K>
 */
public class HazelcastRepository<K, T extends Identifiable<K>> implements Repository<K, T> {

    private final IMap<K, T> map;

    /**
     * Constructor for HazelcastRepository.
     * @param map that this class will wrap
     */
    public HazelcastRepository(IMap<K, T> map) {
        this.map = map;
    }

    /**
     * Get mapped value
     * @param id key of the entity
     * @return
     */
    @Override
    public Optional<T> get(K id) {
        return Optional.ofNullable(map.get(id));
    }

    /**
     * Store entity
     * @param entity to be stored
     */
    @Override
    public void put(T entity) {
        map.put(entity.getId(), entity);
    }

    /**
     * Get all mapped values
     * @return Collection of all entities
     */
    @Override
    public Collection<T> getAll() {
        return map.values();
    }

    /**
     * Delete entity
     * @param id key of the entity
     */
    @Override
    public void delete(K id) {
        map.remove(id);
    }

    /**
     * Returns the underlying IMap for advanced operations.
     * @return the Hazelcast IMap
     */
    protected IMap<K, T> getMap() {
        return map;
    }
}


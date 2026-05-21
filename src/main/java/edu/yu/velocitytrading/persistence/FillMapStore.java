package edu.yu.velocitytrading.persistence;

import com.hazelcast.map.MapStore;
import edu.yu.velocitytrading.model.Fill;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hazelcast MapStore implementation for Fill records.
 * This class bridges the Hazelcast IMap with PostgreSQL persistence
 * by converting between Fill records and FillEntity objects.
 */
public class FillMapStore implements MapStore<UUID, Fill> {

    private final BaseJpaRepository<FillEntity, UUID> repository;

    public FillMapStore(BaseJpaRepository<FillEntity, UUID> repository) {
        this.repository = repository;
    }

    // --- MapStore Write Methods ---

    @Override
    public void store(UUID key, Fill fill) {
        FillEntity entity = FillEntity.fromRecord(fill);
        repository.save(entity);
    }

    @Override
    public void storeAll(Map<UUID, Fill> map) {
        var entities = map.values().stream()
                .map(FillEntity::fromRecord)
                .collect(Collectors.toList());
        repository.saveAll(entities);
    }

    @Override
    public void delete(UUID key) {
        repository.deleteById(key);
    }

    @Override
    public void deleteAll(Collection<UUID> keys) {
        repository.deleteAllById(keys);
    }

    // --- MapLoader Read Methods ---

    @Override
    public Fill load(UUID key) {
        return repository.findById(key)
                .map(FillEntity::toRecord)
                .orElse(null);
    }

    @Override
    public Map<UUID, Fill> loadAll(Collection<UUID> keys) {
        return repository.findAllById(keys).stream()
                .collect(Collectors.toMap(
                        FillEntity::getOrderId,
                        FillEntity::toRecord
                ));
    }

    @Override
    public Iterable<UUID> loadAllKeys() {
        return repository.findAll().stream()
                .map(FillEntity::getOrderId)
                .collect(Collectors.toList());
    }
}


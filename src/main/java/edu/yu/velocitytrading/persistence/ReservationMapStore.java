package edu.yu.velocitytrading.persistence;

import com.hazelcast.map.MapStore;
import edu.yu.velocitytrading.model.Reservation;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hazelcast MapStore implementation for Reservation records.
 * This class bridges the Hazelcast IMap with PostgreSQL persistence
 * by converting between Reservation records and ReservationEntity objects.
 */
public class ReservationMapStore implements MapStore<String, Reservation> {

    private final BaseJpaRepository<ReservationEntity, String> repository;

    public ReservationMapStore(BaseJpaRepository<ReservationEntity, String> repository) {
        this.repository = repository;
    }

    // --- MapStore Write Methods ---

    @Override
    public void store(String key, Reservation reservation) {
        ReservationEntity entity = ReservationEntity.fromRecord(reservation);
        repository.save(entity);
    }

    @Override
    public void storeAll(Map<String, Reservation> map) {
        var entities = map.values().stream()
                .map(ReservationEntity::fromRecord)
                .collect(Collectors.toList());
        repository.saveAll(entities);
    }

    @Override
    public void delete(String key) {
        repository.deleteById(key);
    }

    @Override
    public void deleteAll(Collection<String> keys) {
        repository.deleteAllById(keys);
    }

    // --- MapLoader Read Methods ---

    @Override
    public Reservation load(String key) {
        return repository.findById(key)
                .map(ReservationEntity::toRecord)
                .orElse(null);
    }

    @Override
    public Map<String, Reservation> loadAll(Collection<String> keys) {
        return repository.findAllById(keys).stream()
                .collect(Collectors.toMap(
                        entity -> entity.getId().toString(),
                        entity -> entity.toRecord()
                ));
    }

    @Override
    public Iterable<String> loadAllKeys() {
        return repository.findAll().stream()
                .map(entity -> entity.getId().toString())
                .collect(Collectors.toList());
    }
}


package edu.yu.velocitytrading.persistence;

/**
 * Interface for JPA entities that can provide their own unique identifier.
 * This mirrors the Identifiable interface in the model package,
 * providing a consistent pattern for entity key extraction.
 *
 * @param <K> the type of the key/identifier
 */
public interface IdentifiableEntity<K> {
    /**
     * Returns the unique identifier for this entity.
     * @return the unique key
     */
    K getId();
}


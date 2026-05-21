package edu.yu.velocitytrading.model;

/**
 * Interface for models that can provide their own unique identifier.
 * This allows for a generic repository pattern where each model
 * defines how to extract its key.
 *
 * @param <K> the type of the key/identifier
 */
public interface Identifiable<K> {
    /**
     * Returns the unique identifier for this entity.
     * @return the unique key
     */
    K getId();
}


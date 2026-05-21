/**
 * In-memory data access abstractions for the Velocity Trading application.
 *
 * <p>This package provides a generic repository abstraction and its Hazelcast-backed
 * implementation for distributed in-memory storage:
 * <ul>
 *   <li>{@link edu.yu.velocitytrading.memory.Repository} - Generic repository interface
 *       defining standard CRUD operations for {@link edu.yu.velocitytrading.model.Identifiable}
 *       entities.</li>
 *   <li>{@link edu.yu.velocitytrading.memory.HazelcastRepository} - Hazelcast-backed
 *       implementation of {@code Repository}, storing entries in a distributed
 *       {@code IMap} with optional JPA-based map store persistence.</li>
 * </ul>
 */
package edu.yu.velocitytrading.memory;

/**
 * JPA-based persistence infrastructure for the Velocity Trading application.
 *
 * <p>This package provides the persistence layer used to back Hazelcast's distributed
 * in-memory storage with durable JPA/database storage via Hazelcast {@code MapStore}
 * integrations:
 * <ul>
 *   <li>{@link edu.yu.velocitytrading.persistence.IdentifiableEntity} - Base JPA entity
 *       providing a common identity contract for all persistable domain objects.</li>
 *   <li>{@link edu.yu.velocitytrading.persistence.BaseJpaRepository} - Base Spring Data
 *       JPA repository interface for {@code IdentifiableEntity} subclasses.</li>
 *   <li>{@link edu.yu.velocitytrading.persistence.ExternalOrderEntity} - JPA entity
 *       representing a persisted {@link edu.yu.velocitytrading.model.ExternalOrder}.</li>
 *   <li>{@link edu.yu.velocitytrading.persistence.ExternalOrderMapStore} - Hazelcast
 *       {@code MapStore} bridging the {@code ExternalOrder} distributed map to JPA.</li>
 *   <li>{@link edu.yu.velocitytrading.persistence.FillEntity} - JPA entity representing
 *       a persisted {@link edu.yu.velocitytrading.model.Fill}.</li>
 *   <li>{@link edu.yu.velocitytrading.persistence.FillMapStore} - Hazelcast {@code MapStore}
 *       bridging the {@code Fill} distributed map to JPA.</li>
 *   <li>{@link edu.yu.velocitytrading.persistence.PositionEntity} - JPA entity representing
 *       a persisted {@link edu.yu.velocitytrading.model.Position}.</li>
 *   <li>{@link edu.yu.velocitytrading.persistence.PositionMapStore} - Hazelcast {@code MapStore}
 *       bridging the {@code Position} distributed map to JPA.</li>
 * </ul>
 */
package edu.yu.velocitytrading.persistence;

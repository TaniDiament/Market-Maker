/**
 * Persistence interfaces for the Market Maker application.
 *
 * <p>This package defines the contracts for JPA-based persistence infrastructure,
 * providing abstractions over Spring Data repositories and map store integrations
 * used by the Hazelcast-backed distributed storage layer:
 * <ul>
 *   <li>{@link edu.yu.velocitytrading.persistence.BaseJpaRepository} - Base
 *       Spring Data JPA repository interface providing common CRUD and query operations
 *       for {@link edu.yu.velocitytrading.persistence.IdentifiableEntity} subclasses.</li>
 * </ul>
 */
package edu.yu.velocitytrading.persistence.interfaces;

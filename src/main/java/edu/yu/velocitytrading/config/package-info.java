/**
 * Hazelcast configuration classes for the Market Maker application.
 *
 * <p>This package contains Spring and infrastructure configuration, including:
 * <ul>
 *   <li>{@link edu.yu.velocitytrading.config.HazelcastConfig} - Configures the embedded
 *       Hazelcast instance used for distributed in-memory storage, including network
 *       discovery (multicast for development, TCP-IP/Kubernetes for production) and
 *       map store persistence via JPA.</li>
 * </ul>
 */
package edu.yu.velocitytrading.config;
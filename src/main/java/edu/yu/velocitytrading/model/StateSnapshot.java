package edu.yu.velocitytrading.model;

/**
 * A snapshot of a single position together with the fill that triggered
 * the position change.  Sent as individual stream items to RSocket subscribers.
 *
 * @param position the current net position for a symbol
 * @param fill     the fill that triggered this position change
 */
public record StateSnapshot(Position position, Fill fill) {}
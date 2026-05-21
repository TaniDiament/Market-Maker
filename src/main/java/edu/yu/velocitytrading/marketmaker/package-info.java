/**
 * Core market-making logic: turning position state into quotes.
 *
 * A market-maker node owns a subset of symbols (assigned by the
 * {@link edu.yu.velocitytrading.cluster cluster} package) and for each owned symbol
 * consumes position/fill updates, tracks inventory, and produces bid/ask quotes
 * that get published back to the exchange.
 *
 * Key responsibilities:
 * <ul>
 *   <li>Driver loop that reacts to state changes ({@link edu.yu.velocitytrading.marketmaker.MarketMaker}).</li>
 *   <li>Position and snapshot tracking per owned symbol
 *       ({@link edu.yu.velocitytrading.marketmaker.PositionTracker},
 *       {@link edu.yu.velocitytrading.marketmaker.SnapshotTracker}).</li>
 *   <li>Quote generation strategies ({@link edu.yu.velocitytrading.marketmaker.QuoteGenerator},
 *       {@link edu.yu.velocitytrading.marketmaker.ProductionQuoteGenerator},
 *       {@link edu.yu.velocitytrading.marketmaker.TestQuoteGenerator}).</li>
 *   <li>Status/introspection endpoints
 *       ({@link edu.yu.velocitytrading.marketmaker.MarketMakerStatusController}).</li>
 * </ul>
 */
package edu.yu.velocitytrading.marketmaker;

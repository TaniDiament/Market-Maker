package edu.yu.marketmaker.ha;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zookeeper-backed service discovery for the current leader of a given service.
 *
 * <p>Two sides to this:
 * <ul>
 *   <li><b>Publishing</b> ({@link #publishEndpoint}) — called by a replica when it
 *       wins leader election. Creates an ephemeral node at
 *       {@code /mm/endpoints/<service>} with its address. The node disappears
 *       automatically if the replica's ZK session dies.</li>
 *   <li><b>Consuming</b> ({@link #getLeaderAddress}) — called by any client. Uses
 *       a {@link CuratorCache} to watch the endpoint path and keep a local cache
 *       current; lookup is non-blocking.</li>
 * </ul>
 */
public class ServiceRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);
    private static final String ENDPOINT_BASE_PATH = "/mm/endpoints";

    private final CuratorFramework curator;
    private final ObjectMapper mapper = new ObjectMapper();

    // service-name -> cache of its endpoint node
    private final ConcurrentHashMap<String, CuratorCache> caches = new ConcurrentHashMap<>();
    // service-name -> last known leader address
    private final ConcurrentHashMap<String, Endpoint> leaders = new ConcurrentHashMap<>();

    public ServiceRegistry(CuratorFramework curator) {
        this.curator = curator;
    }

    /**
     * Called by the leader replica (from {@code onLeaderAcquired}) to advertise itself.
     * The node is ephemeral — if this JVM dies or loses ZK session, the entry vanishes
     * and the next leader will publish theirs.
     */
    public void publishEndpoint(String serviceName, String host, int httpPort, int rsocketPort) {
        String path = ENDPOINT_BASE_PATH + "/" + serviceName;
        try {
            Endpoint ep = new Endpoint(host, httpPort, rsocketPort);
            byte[] payload = mapper.writeValueAsBytes(ep);

            // Ensure parent exists
            try {
                curator.create().creatingParentsIfNeeded().forPath(ENDPOINT_BASE_PATH);
            } catch (KeeperException.NodeExistsException ignored) {}

            // Delete any stale entry first (e.g. previous leader in same JVM lifecycle)
            try {
                curator.delete().forPath(path);
            } catch (KeeperException.NoNodeException ignored) {}

            curator.create()
                   .withMode(CreateMode.EPHEMERAL)
                   .forPath(path, payload);

            log.warn("[{}] published leader endpoint {}:{} (rsocket={})",
                    serviceName, host, httpPort, rsocketPort);
        } catch (Exception e) {
            log.error("[{}] failed to publish endpoint", serviceName, e);
        }
    }

    /**
     * Called from {@code onLeaderLost}. Not strictly required — the ephemeral node will
     * vanish anyway when the session closes — but it speeds up failover visibility.
     */
    public void unpublishEndpoint(String serviceName) {
        String path = ENDPOINT_BASE_PATH + "/" + serviceName;
        try {
            curator.delete().forPath(path);
            log.info("[{}] unpublished leader endpoint", serviceName);
        } catch (KeeperException.NoNodeException ignored) {
        } catch (Exception e) {
            log.warn("[{}] failed to unpublish endpoint: {}", serviceName, e.getMessage());
        }
    }

    /**
     * Look up the current leader of {@code serviceName}. Uses a cached watcher
     * so this is cheap (no ZK round trip per call after the first).
     *
     * @return present if a leader is currently registered, empty otherwise
     */
    public Optional<Endpoint> getLeaderAddress(String serviceName) {
        ensureWatching(serviceName);
        return Optional.ofNullable(leaders.get(serviceName));
    }

    private void ensureWatching(String serviceName) {
        caches.computeIfAbsent(serviceName, s -> {
            String path = ENDPOINT_BASE_PATH + "/" + s;
            CuratorCache cache = CuratorCache.build(curator, path);

            cache.listenable().addListener((type, oldData, newData) -> {
                switch (type) {
                    case NODE_CREATED:
                    case NODE_CHANGED:
                        Endpoint ep = parse(newData);
                        if (ep != null) {
                            leaders.put(s, ep);
                            log.info("[{}] leader endpoint updated -> {}", s, ep);
                        }
                        break;
                    case NODE_DELETED:
                        leaders.remove(s);
                        log.warn("[{}] leader endpoint removed — no leader currently", s);
                        break;
                }
            });
            cache.start();
            return cache;
        });
    }

    private Endpoint parse(ChildData data) {
        if (data == null || data.getData() == null) return null;
        try {
            return mapper.readValue(data.getData(), Endpoint.class);
        } catch (Exception e) {
            log.error("failed to parse endpoint payload", e);
            return null;
        }
    }

    @Override
    public void destroy() {
        caches.values().forEach(CuratorCache::close);
        caches.clear();
    }

    /** Endpoint record published to ZK. Public fields for Jackson. */
    public static class Endpoint {
        public String host;
        public int httpPort;
        public int rsocketPort;

        public Endpoint() {}

        public Endpoint(String host, int httpPort, int rsocketPort) {
            this.host = host;
            this.httpPort = httpPort;
            this.rsocketPort = rsocketPort;
        }

        @Override
        public String toString() {
            return host + ":" + httpPort + " (rsocket=" + rsocketPort + ")";
        }
    }
}

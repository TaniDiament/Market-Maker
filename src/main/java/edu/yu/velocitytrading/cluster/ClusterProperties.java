package edu.yu.velocitytrading.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-tunable cluster settings. Loaded from
 * {@code application-market-maker-node.properties}, overridable via env vars
 * (e.g. {@code ZOOKEEPER_CONNECT}). Defaults work out-of-the-box in the
 * Compose stack.
 */
@ConfigurationProperties(prefix = "cluster")
public class ClusterProperties {

    private String zookeeperConnect = "zookeeper:2181";
    private String zkBasePath = "/marketmaker";
    private String symbolsSeedFile = "/config/symbols.txt";
    private int sessionTimeoutMs = 10_000;
    private int connectionTimeoutMs = 5_000;
    private String advertiseHost = "localhost";
    private int forwardPort = 7001;
    private String nodeId = "";

    /** @return the ZK connection string ({@code host:port[,host:port...]}). */
    public String getZookeeperConnect() {
        return zookeeperConnect;
    }

    public void setZookeeperConnect(String zookeeperConnect) {
        this.zookeeperConnect = zookeeperConnect;
    }

    /** @return the cluster's root znode path. */
    public String getZkBasePath() {
        return zkBasePath;
    }

    public void setZkBasePath(String zkBasePath) {
        this.zkBasePath = zkBasePath;
    }

    /** @return path to the symbol seed file. */
    public String getSymbolsSeedFile() {
        return symbolsSeedFile;
    }

    public void setSymbolsSeedFile(String symbolsSeedFile) {
        this.symbolsSeedFile = symbolsSeedFile;
    }

    /** @return ZK session timeout in ms (governs how fast ephemeral znodes expire on node death). */
    public int getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public void setSessionTimeoutMs(int sessionTimeoutMs) {
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    /** @return ZK TCP connection timeout in ms (distinct from session timeout). */
    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /**
     * @return hostname this node publishes so peers can open TCP forwarding
     *         connections to it. Must be resolvable from every other node
     *         (in Docker compose, set this to the service name).
     */
    public String getAdvertiseHost() {
        return advertiseHost;
    }

    public void setAdvertiseHost(String advertiseHost) {
        this.advertiseHost = advertiseHost;
    }

    /** @return TCP port this node's {@code WorkerForwardReceiver} listens on. */
    public int getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(int forwardPort) {
        this.forwardPort = forwardPort;
    }

    /**
     * @return stable cluster id for this JVM. When set (e.g. to {@code POD_NAME}
     *         in k8s or the service hostname in compose), the member znode is
     *         created at a fixed path and recreated automatically by Curator
     *         after ZK session loss. When blank, the legacy ephemeral-sequential
     *         path is used (useful for unit tests against a {@code TestingServer}).
     */
    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId == null ? "" : nodeId;
    }
}

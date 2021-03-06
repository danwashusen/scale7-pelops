package org.scale7.cassandra.pelops.pool;

import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.commons.pool.BaseKeyedPoolableObjectFactory;
import org.apache.commons.pool.KeyedObjectPool;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;
import org.apache.thrift.TException;
import org.scale7.cassandra.pelops.*;
import org.scale7.cassandra.pelops.exceptions.NoConnectionsAvailableException;
import org.scale7.cassandra.pelops.exceptions.PelopsException;
import org.scale7.portability.SystemProxy;
import org.slf4j.Logger;

import java.net.SocketException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CommonsBackedPool extends ThriftPoolBase {
    private static final Logger logger = SystemProxy.getLoggerFromFactory(CommonsBackedPool.class);

    private static final int DEFAULT_WAIT_PERIOD = 100;

    private final Cluster cluster;

    private final Policy policy;
    private final String keyspace;
    private final OperandPolicy operandPolicy;

    private final INodeSelectionStrategy nodeSelectionStrategy;
    private final INodeSuspensionStrategy nodeSuspensionStrategy;

    private final Map<String, PooledNode> nodes = new ConcurrentHashMap<String, PooledNode>();
    private GenericKeyedObjectPool pool;

    private ScheduledExecutorService executorService;
    private final Object scheduledTasksLock = new Object();

    /* running stats */
    private RunningStatistics statistics;

    public CommonsBackedPool(Cluster cluster, Policy policy, OperandPolicy operandPolicy, String keyspace, INodeSelectionStrategy nodeSelectionStrategy, INodeSuspensionStrategy nodeSuspensionStrategy) {
        this.cluster = cluster;
        this.policy = policy;
        this.operandPolicy = operandPolicy;
        this.keyspace = keyspace;

        this.nodeSelectionStrategy = nodeSelectionStrategy;
        this.nodeSuspensionStrategy = nodeSuspensionStrategy;

        this.statistics = new RunningStatistics();

        logger.info("Initialising pool configuration policy: {}", policy.toString());

        configureBackingPool();

        Cluster.Node[] currentNodes = cluster.getNodes();
        logger.info("Pre-initialising connections for nodes: {}", Arrays.toString(currentNodes));
        for (Cluster.Node node : currentNodes) {
            addNode(node.getAddress());
        }
        statistics.nodesActive.set(this.nodes.size());

        configureScheduledTasks();
    }

    private void configureScheduledTasks() {
        if (policy.getTimeBetweenScheduledTaskRunsMillis() > 0) {
            logger.info("Configuring scheduled tasks to run every {} milliseconds", policy.getTimeBetweenScheduledTaskRunsMillis());
            executorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "pelops-pool-watcher-" + getKeyspace());
                    thread.setDaemon(false); // don't make the JVM wait for this thread to exit
                    thread.setPriority(Thread.MIN_PRIORITY + 1); // try not to disrupt other threads
                    return thread;
                }
            });

            executorService.scheduleWithFixedDelay(
                    new Runnable() {
                        @Override
                        public void run() {
                            logger.debug("Running scheduled tasks");
                            try {
                                runScheduledTasks();
                            } catch (Exception e) {
                                logger.warn("An exception was thrown while running the scheduled tasks", e);
                            }
                        }
                    },
                    policy.getTimeBetweenScheduledTaskRunsMillis(),
                    policy.getTimeBetweenScheduledTaskRunsMillis(),
                    TimeUnit.MILLISECONDS
            );
        } else {
            logger.warn("Disabling scheduled tasks; dynamic node discovery, node suspension, idle connection " +
                    "termination and some running statistics will not be available to this pool.");
        }
    }

    protected void configureBackingPool() {
        pool = new GenericKeyedObjectPool(new ConnectionFactory());
        pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_BLOCK);
        pool.setMaxWait(DEFAULT_WAIT_PERIOD);
        pool.setLifo(true);
        pool.setMaxActive(policy.getMaxActivePerNode());
        pool.setMinIdle(policy.getMinIdlePerNode());
        pool.setMaxIdle(policy.getMaxIdlePerNode());
        pool.setMaxTotal(policy.getMaxTotal());
        pool.setTimeBetweenEvictionRunsMillis(-1); // we don't want to eviction thread running
        pool.setTestOnReturn(true); // in case the connection is corrupt
    }

    protected void runScheduledTasks() {
        logger.debug("Attempting to acquire lock for scheduled tasks");
        synchronized (scheduledTasksLock) {
            logger.debug("Starting scheduled tasks");
            // add/remove any new/dead nodes
            handleClusterRefresh();

            // check which nodes should be suspended
            logger.debug("Evaluating which nodes should be suspended");
            int nodesSuspended = 0;
            for (PooledNode node : nodes.values()) {
                logger.debug("Evaluating if node {} should be suspended", node.getAddress());
                if (nodeSuspensionStrategy.evaluate(this, node)) {
                    nodesSuspended++;
                    logger.info("Node {} was suspended from the pool, closing existing pooled connections", node.getAddress());
                    // remove any existing connections
                    pool.clear(node.getAddress());
                    node.reportSuspension();
                }
            }
            statistics.nodesActive.set(nodes.size() - nodesSuspended);
            statistics.nodesSuspended.set(nodesSuspended);

            try {
                logger.debug("Evicting idle nodes based on configuration rules");
                pool.evict();
            } catch (Exception e) {
                // do nothing
            }
            logger.debug("Finished scheduled tasks");
        }
    }

    private void handleClusterRefresh() {
        cluster.refresh();
        Cluster.Node[] currentNodes = cluster.getNodes();
        logger.debug("Determining which nodes need to be added and removed based on latest nodes list");
        // figure out which of the nodes are new
        for (Cluster.Node node : currentNodes) {
            if (!this.nodes.containsKey(node.getAddress())) {
                addNode(node.getAddress());
            }
        }

        // figure out which nodes need to be removed
        for (String nodeAddress : this.nodes.keySet()) {
            boolean isPresent = false;
            for (Cluster.Node node : currentNodes) {
                if (node.getAddress().equals(nodeAddress)) {
                    isPresent = true;
                }
            }

            if (!isPresent) {
                removeNode(nodeAddress);
            }
        }
    }

    private void addNode(String nodeAddress) {
        logger.info("Adding node '{}' to the pool...", nodeAddress);

        // initialise (JMX etc)
        PooledNode node = new PooledNode(pool, nodeAddress);

        // add it as a candidate
        nodes.put(nodeAddress, node);

        // prepare min idle connetions etc...
        // NOTE: there's a potential for the node to be selected as a candidate before it's been prepared
        //       but preparing before adding means the stats don't get updated
        pool.preparePool(nodeAddress, true);
    }

    private void removeNode(String nodeAddress) {
        logger.info("Removing node '{}' from the pool", nodeAddress);

        // remove from the the nodes list so it's no longer considered a candidate
        PooledNode node = nodes.remove(nodeAddress);

        // shutdown all the connections and clear it from the backing pool
        pool.clear(nodeAddress);

        // decommission (JMX etc)
        if (node != null) {
            node.decommission();
        }
    }

    @Override
    public IPooledConnection getConnection() throws NoConnectionsAvailableException {
        return getConnectionExcept(null);
    }

    @Override
    public IPooledConnection getConnectionExcept(String notNodeHint) throws NoConnectionsAvailableException {
        PooledNode node = null;
        IPooledConnection connection = null;
        long timeout = -1;

        while (connection == null) {
            if (timeout == -1) {
                // first run through calc the timeout for the next loop
                // (this makes debugging easier)
                timeout = getConfig().getMaxWaitForConnection() > 0 ?
                        System.currentTimeMillis() + getConfig().getMaxWaitForConnection() :
                        Long.MAX_VALUE;
            } else if (timeout < System.currentTimeMillis()) {
                logger.debug("Max time for connection exceeded");
                break;
            }

            node = nodeSelectionStrategy.select(this, nodes.keySet(), notNodeHint);
            // if the strategy was unable to choose a node (all suspended?) then sleep for a bit and loop
            if (node == null) {
                logger.debug("The node selection strategy was unable to choose a node, sleeping before trying again...");
                try {
                    Thread.sleep(DEFAULT_WAIT_PERIOD);
                } catch (InterruptedException e) {
                    // do nothing
                }
                continue;
            }

            try {
                logger.debug("Attempting to borrow free connection for node '{}'", node.getAddress());
                // note that if no connections are currently available for this node then the pool will sleep for
                // DEFAULT_WAIT_PERIOD milliseconds
                connection = (IPooledConnection) pool.borrowObject(node.getAddress());
            } catch (NoSuchElementException e) {
                logger.debug("No free connections available for node '{}', trying another node...", node.getAddress());
            } catch (IllegalStateException e) {
                throw new PelopsException("The pool has been shutdown", e);
            } catch (Exception e) {
                logger.warn(String.format("An exception was thrown while attempting to create a connection to '%s', " +
                        "trying another node...", node.getAddress()), e);
            }
        }

        if (node == null) {
            logger.error(
                    "Failed to get a connection within the configured wait time because there are no available nodes. " +
                            "This possibly indicates that either the suspension strategy is too aggressive or that your " +
                            "cluster is in a bad way."
            );
            throw new NoConnectionsAvailableException("Failed to get a connection within the configured max wait time.");
        }

        if (connection == null) {
            logger.error(
                    "Failed to get a connection within the maximum allowed wait time.  " +
                            "Try increasing the either the number of allowed connections or the max wait time."
            );
            throw new NoConnectionsAvailableException("Failed to get a connection within the configured max wait time.");
        }

        logger.debug("Borrowing connection '{}'", connection);
        statistics.connectionsActive.incrementAndGet();
        reportConnectionBorrowed(connection.getNode().getAddress());
        return connection;
    }

    protected void releaseConnection(PooledConnection connection) {
        logger.debug("Returning connection '{}'", connection);
        try {
            pool.returnObject(connection.getNode().getAddress(), connection);
            statistics.connectionsActive.decrementAndGet();
            reportConnectionReleased(connection.getNode().getAddress());
        } catch (Exception e) {
            // do nothing
        }
    }

    @Override
    public void shutdown() {
        if (executorService != null) {
            logger.debug("Terminating background thread...");
            executorService.shutdownNow();
        }

        try {
            logger.debug("Closing pooled connections...");
            pool.close();
        } catch (Exception e) {
            logger.error("Failed to close pool", e);
        }
    }

    @Override
    public OperandPolicy getOperandPolicy() {
        return operandPolicy;
    }

    @Override
    public String getKeyspace() {
        return keyspace;
    }

    public Policy getConfig() {
        return policy;
    }

    /**
     * Returns the pooled node instance for the nodeAddress.
     *
     * @param nodeAddress the node address
     * @return the pooled node instance or null if the nodeAddress doesn't match a pooled node
     */
    public PooledNode getPooledNode(String nodeAddress) {
        return nodes.get(nodeAddress);
    }

    protected void reportConnectionCreated(String nodeAddress) {
        statistics.connectionsCreated.incrementAndGet();

        PooledNode node = getPooledNode(nodeAddress);

        if (node != null)
            node.reportConnectionCreated();
    }

    protected void reportConnectionDestroyed(String nodeAddress) {
        statistics.connectionsDestroyed.incrementAndGet();

        PooledNode node = getPooledNode(nodeAddress);

        if (node != null)
            node.reportConnectionDestroyed();
    }

    protected void reportConnectionCorrupted(String nodeAddress) {
        statistics.connectionsCorrupted.incrementAndGet();

        PooledNode pooledNode = getPooledNode(nodeAddress);

        if (pooledNode != null)  // it's possible that the pooled node has been removed
            pooledNode.reportConnectionCorrupted();
    }

    protected void reportConnectionBorrowed(String nodeAddress) {
        statistics.connectionsBorrowedTotal.incrementAndGet();

        PooledNode pooledNode = getPooledNode(nodeAddress);

        if (pooledNode != null)  // it's possible that the pooled node has been removed
            pooledNode.reportConnectionBorrowed();
    }

    protected void reportConnectionReleased(String nodeAddress) {
        statistics.connectionsReleasedTotal.incrementAndGet();

        PooledNode pooledNode = getPooledNode(nodeAddress);

        if (pooledNode != null)  // it's possible that the pooled node has been removed
            pooledNode.reportConnectionReleased();
    }

    public RunningStatistics getStatistics() {
        return statistics;
    }

    public static class Policy {
        private int maxActivePerNode = 20;
        private int maxTotal = -1;
        private int maxIdlePerNode = 10;
        private int minIdlePerNode = 10;
        private int maxWaitForConnection = 1000;
        private int timeBetweenScheduledTaskRunsMillis = 1000 * 60;

        public Policy() {
        }

        /**
         * @see #setMaxActivePerNode(int)
         */
        public int getMaxActivePerNode() {
            return maxActivePerNode;
        }

        /**
         * Sets the cap on the number of object instances managed by the pool per node.
         *
         * @param maxActivePerNode The cap on the number of object instances per node. Use a negative value for no limit.
         */
        public void setMaxActivePerNode(int maxActivePerNode) {
            this.maxActivePerNode = maxActivePerNode;
        }

        public int getMaxIdlePerNode() {
            return maxIdlePerNode;
        }

        /**
         * Sets the cap on the number of "idle" instances in the pool. If maxIdle is set too low on heavily loaded
         * systems it is possible you will see objects being destroyed and almost immediately new objects being created.
         * This is a result of the active threads momentarily returning objects faster than they are requesting them
         * them, causing the number of idle objects to rise above maxIdle. The best value for maxIdle for heavily
         * loaded system will vary but the default is a good starting point.
         *
         * @param maxIdlePerNode
         */
        public void setMaxIdlePerNode(int maxIdlePerNode) {
            this.maxIdlePerNode = maxIdlePerNode;
        }

        /**
         * @see #setMaxTotal(int)
         */
        public int getMaxTotal() {
            return maxTotal;
        }

        /**
         * Sets the cap on the total number of instances from all nodes combined. When maxTotal is set to a positive
         * value and {@link CommonsBackedPool#getConnection()} is invoked when at the limit with no idle instances
         * available, an attempt is made to create room by clearing the oldest 15% of the elements from the keyed pools.
         *
         * @param maxTotal The cap on the number of object instances per node. Use a negative value for no limit.
         */
        public void setMaxTotal(int maxTotal) {
            this.maxTotal = maxTotal;
        }

        /**
         * @see #setMinIdlePerNode(int)
         */
        public int getMinIdlePerNode() {
            return minIdlePerNode;
        }

        /**
         * Sets the minimum number of idle objects to maintain in each of the nodes.
         *
         * @param minIdlePerNode The minimum size of the each nodes pool
         */
        public void setMinIdlePerNode(int minIdlePerNode) {
            this.minIdlePerNode = minIdlePerNode;
        }

        /**
         * @see #setMaxWaitForConnection(int)
         */
        public int getMaxWaitForConnection() {
            return maxWaitForConnection;
        }

        /**
         * Sets the maximum amount of time (in milliseconds) the {@link CommonsBackedPool#getConnection()} method should
         * wait before throwing an exception when the pool is exhausted.  When less than or equal to 0, the
         * {@link CommonsBackedPool#getConnection()} method may block indefinitely.
         *
         * @param maxWaitForConnection the maximum number of milliseconds {@link CommonsBackedPool#getConnection()}
         *                             will block or negative for indefinitely.
         */
        public void setMaxWaitForConnection(int maxWaitForConnection) {
            this.maxWaitForConnection = maxWaitForConnection;
        }

        /**
         * @see #setTimeBetweenScheduledTaskRunsMillis(int)
         */
        public int getTimeBetweenScheduledTaskRunsMillis() {
            return timeBetweenScheduledTaskRunsMillis;
        }

        /**
         * Sets the number of milliseconds to sleep between runs of the idle object tasks thread. When non-positive,
         * no idle object evictor thread will be run.
         *
         * @param timeBetweenScheduledTaskRunsMillis
         *         milliseconds to sleep between evictor runs.
         */
        public void setTimeBetweenScheduledTaskRunsMillis(int timeBetweenScheduledTaskRunsMillis) {
            this.timeBetweenScheduledTaskRunsMillis = timeBetweenScheduledTaskRunsMillis;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("Config");
            sb.append("{maxActivePerNode=").append(maxActivePerNode);
            sb.append(", maxTotal=").append(maxTotal);
            sb.append(", maxIdlePerNode=").append(maxIdlePerNode);
            sb.append(", minIdlePerNode=").append(minIdlePerNode);
            sb.append(", maxWaitForConnection=").append(maxWaitForConnection);
            sb.append('}');
            return sb.toString();
        }
    }

    public static class PooledNode {
        private KeyedObjectPool pool;
        private String address;
        private INodeSuspensionState suspensionState;
        private AtomicInteger suspensions;
        private AtomicInteger connectionsCorrupted;
        private AtomicInteger connectionsCreated;
        private AtomicInteger connectionsDestroyed;
        private AtomicInteger connectionsBorrowedTotal;
        private AtomicInteger connectionsReleasedTotal;

        public PooledNode(KeyedObjectPool pool, String address) {
            this.pool = pool;
            this.address = address;
            suspensions = new AtomicInteger();
            connectionsCorrupted = new AtomicInteger();
            connectionsCreated = new AtomicInteger();
            connectionsDestroyed = new AtomicInteger();
            connectionsBorrowedTotal = new AtomicInteger();
            connectionsReleasedTotal = new AtomicInteger();
        }

        public void decommission() {

        }

        public String getAddress() {
            return address;
        }

        public INodeSuspensionState getSuspensionState() {
            return suspensionState;
        }

        public void setSuspensionState(INodeSuspensionState suspensionState) {
            this.suspensionState = suspensionState;
        }

        void reportSuspension() {
            suspensions.incrementAndGet();
        }

        public int getSuspensions() {
            return suspensions.get();
        }

        public int getNumActive() {
            return pool.getNumActive(address);
        }

        public int getNumIdle() {
            return pool.getNumIdle(address);
        }

        void reportConnectionCorrupted() {
            connectionsCorrupted.incrementAndGet();
        }

        public int getConnectionsCorrupted() {
            return connectionsCorrupted.get();
        }

        void reportConnectionCreated() {
            connectionsCreated.incrementAndGet();
        }

        public int getConnectionsCreated() {
            return connectionsCreated.get();
        }

        void reportConnectionDestroyed() {
            connectionsDestroyed.incrementAndGet();
        }

        public int getConnectionsDestroyed() {
            return connectionsDestroyed.get();
        }

        void reportConnectionBorrowed() {
            connectionsBorrowedTotal.incrementAndGet();
        }

        public int getConnectionsBorrowedTotal() {
            return connectionsBorrowedTotal.get();
        }

        void reportConnectionReleased() {
            connectionsReleasedTotal.incrementAndGet();
        }

        public int getConnectionsReleasedTotal() {
            return connectionsReleasedTotal.get();
        }

        public boolean isSuspended() {
            return getSuspensionState() != null && getSuspensionState().isSuspended();
        }
    }

    public class PooledConnection extends Connection implements IPooledConnection {
        private boolean corrupt = false;

        public PooledConnection(Cluster.Node node, String keyspace) throws SocketException, TException, InvalidRequestException {
            super(node, keyspace);
        }

        @Override
        public void release() {
            releaseConnection(this);
        }

        @Override
        public void corrupted() {
            corrupt = true;
        }

        public boolean isCorrupt() {
            return corrupt;
        }

        @Override
        public String toString() {
            return String.format("Connection[%s][%s:%s][%s]", getKeyspace(), getNode().getAddress(), cluster.getConnectionConfig().getThriftPort(), super.hashCode());
        }
    }

    private class ConnectionFactory extends BaseKeyedPoolableObjectFactory {
        @Override
        public Object makeObject(Object key) throws Exception {
            String nodeAddress = (String) key;
            PooledConnection connection = new PooledConnection(
                    new Cluster.Node(nodeAddress, cluster.getConnectionConfig()), getKeyspace()
            );
            logger.debug("Made new connection '{}'", connection);
            connection.open();

            reportConnectionCreated(nodeAddress);

            return connection;
        }

        @Override
        public void destroyObject(Object key, Object obj) throws Exception {
            String nodeAddress = (String) key;
            PooledConnection connection = (PooledConnection) obj;

            logger.debug("Destroying connection '{}'", connection);

            connection.close();

            reportConnectionDestroyed(nodeAddress);
        }

        @Override
        public boolean validateObject(Object key, Object obj) {
            String nodeAddress = (String) key;
            PooledConnection connection = (PooledConnection) obj;
            if (connection.isCorrupt() || !connection.isOpen()) {
                logger.debug("Connection '{}' is corrupt or no longer open, invalidating...", connection);
                return false;
            } else {
                return true;
            }
        }

        @Override
        public void activateObject(Object key, Object obj) throws Exception {
        }

        @Override
        public void passivateObject(Object key, Object obj) throws Exception {
            String nodeAddress = (String) key;
            PooledConnection connection = (PooledConnection) obj;

            if (connection.isCorrupt())
                reportConnectionCorrupted(nodeAddress);
        }
    }

    public static class RunningStatistics {
        private AtomicInteger nodesActive;
        private AtomicInteger nodesSuspended;
        private AtomicInteger connectionsCreated;
        private AtomicInteger connectionsDestroyed;
        private AtomicInteger connectionsCorrupted;
        private AtomicInteger connectionsActive;
        private AtomicInteger connectionsBorrowedTotal;
        private AtomicInteger connectionsReleasedTotal;

        public RunningStatistics() {
            nodesActive = new AtomicInteger();
            nodesSuspended = new AtomicInteger();
            connectionsCreated = new AtomicInteger();
            connectionsDestroyed = new AtomicInteger();
            connectionsCorrupted = new AtomicInteger();
            connectionsActive = new AtomicInteger();
            connectionsBorrowedTotal = new AtomicInteger();
            connectionsReleasedTotal = new AtomicInteger();
        }

        public int getConnectionsCreated() {
            return connectionsCreated.get();
        }

        public int getConnectionsDestroyed() {
            return connectionsDestroyed.get();
        }

        public int getConnectionsCorrupted() {
            return connectionsCorrupted.get();
        }

        public int getConnectionsActive() {
            return connectionsActive.get();
        }

        public int getNodesActive() {
            return nodesActive.get();
        }

        public int getNodesSuspended() {
            return nodesSuspended.get();
        }

        public int getConnectionsBorrowedTotal() {
            return connectionsBorrowedTotal.get();
        }

        public int getConnectionsReleasedTotal() {
            return connectionsReleasedTotal.get();
        }
    }

    /**
     * Interface used to define how nodes should be selected.
     */
    public static interface INodeSelectionStrategy {
        /**
         * Called when a node need to be selected.
         *
         * @param pool          the pool (just in case you need it)
         * @param nodeAddresses the node addresses to select from
         * @param notNodeHint   a hint of the node address that the selection strategy should avoid (possible null)
         * @return the selected node (null if none are available)
         */
        PooledNode select(CommonsBackedPool pool, Set<String> nodeAddresses, String notNodeHint);
    }

    /**
     * Interface used to define how nodes should be suspended for behaving badly.  For example, if a
     * node is reporting lots of corrupt connections then maybe it should be avoided for a while.
     * <p/>
     * <p>Implementations should indicate if a node is suspended by ensuring that
     * {@link org.scale7.cassandra.pelops.pool.CommonsBackedPool.INodeSuspensionState#isSuspended()} returns true
     * until the node should no longer be considered suspended.
     * <p/>
     * <p>Any state required to determine if a node should be suspended should be stored in the nodes
     * {@link org.scale7.cassandra.pelops.pool.CommonsBackedPool.PooledNode#getSuspensionState()}.  Note that the
     * suspension state may be null if the node has not been evaluated before.
     * <p>Also note that the {@link #evaluate(CommonsBackedPool, org.scale7.cassandra.pelops.pool.CommonsBackedPool.PooledNode)}
     * will be called by the scheduled tasks thread even when the node is currently suspended.
     */
    public static interface INodeSuspensionStrategy {
        /**
         * Called for each node in the pool by the pools background thread.
         *
         * @param pool the pool (just in case you need it)
         * @param node the node to evaluate
         * @return true if the node was suspending, otherwise false
         */
        boolean evaluate(CommonsBackedPool pool, PooledNode node);
    }

    /**
     * Interface used to define a pooled nodes suspension status.
     *
     * @see INodeSuspensionStrategy
     */
    public static interface INodeSuspensionState {
        /**
         * Used to indicate if a node is suspended.
         *
         * @return true if the node is suspended, otherwise false (this method should return true until the node is no
         *         longer considered suspended)
         */
        boolean isSuspended();
    }
}

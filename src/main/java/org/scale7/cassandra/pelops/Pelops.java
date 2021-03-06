package org.scale7.cassandra.pelops;

import java.util.concurrent.ConcurrentHashMap;

import org.scale7.cassandra.pelops.pool.CachePerNodePool;
import org.scale7.cassandra.pelops.pool.IThriftPool;
import org.scale7.portability.SystemProxy;
import org.slf4j.Logger;

public class Pelops {

	private static final Logger logger = SystemProxy.getLoggerFromFactory(Pelops.class);

	private static ConcurrentHashMap<String, IThriftPool> poolMap = new ConcurrentHashMap<String, IThriftPool>();

	/**
	 * Add a new Thrift connection pool for a specific cluster and keyspace. The name given to the pool is later used
	 * when creating operands such as <code>Mutator</code> and <code>Selector</code>.
	 * @param poolName				A name used to reference the pool e.g. "MainDatabase" or "LucandraIndexes"
	 * @param cluster				The Cassandra cluster that network connections will be made to
	 * @param keyspace				The keyspace in the Cassandra cluster against which pool operations will apply
	 */
	public static void addPool(String poolName, Cluster cluster, String keyspace) {
		addPool(poolName, cluster, keyspace, new OperandPolicy(), new CachePerNodePool.Policy());
	}

    /**
	 * Add a new Thrift connection pool for a specific cluster and keyspace. The name given to the pool is later used
	 * when creating operands such as <code>Mutator</code> and <code>Selector</code>.
	 * @param poolName				A name used to reference the pool e.g. "MainDatabase" or "LucandraIndexes"
	 * @param cluster				The Cassandra cluster that network connections will be made to
	 * @param keyspace				The keyspace in the Cassandra cluster against which pool operations will apply
     * @param operandPolicy		    Policy for behaviour of operations made by operands e.g. max operation retries
     * @param poolPolicy            Policy for behaviour of pool e.g. target cached connection count
	 */
	public static void addPool(String poolName, Cluster cluster, String keyspace, OperandPolicy operandPolicy, CachePerNodePool.Policy poolPolicy) {
		CachePerNodePool newPool = new CachePerNodePool(cluster, keyspace, operandPolicy, poolPolicy);
        addPool(poolName, newPool);
	}

    /**
     * Add an already instantiated instance of {@link IThriftPool} to pelops.
     * @param poolName A name used to reference the pool e.g. "MainDatabase" or "LucandraIndexes"
     * @param thriftPool an instance of the {@link IThriftPool} interface
     */
    public static void addPool(String poolName, IThriftPool thriftPool) {
        logger.info("Pelops adds new pool {}", poolName);
        poolMap.put(poolName, thriftPool);
    }

	/**
	 * Shutdown Pelops. This proceeds by shutting down all connection pools.
	 */
	public static void shutdown() {
		logger.info("Pelops starting to shutdown...");
		for (IThriftPool pool : poolMap.values())
			pool.shutdown();
		logger.info("Pelops has shutdown");
	}

	/**
	 * Create a <code>Selector</code> object.
	 * @param poolName				The name of the connection pool to use (this determines the Cassandra database cluster)
	 * @return						A new <code>Selector</code> object
	 */
	public static Selector createSelector(String poolName) {
		return poolMap.get(poolName).createSelector();
	}

	/**
	 * Create a <code>Mutator</code> object using the current time as the operation time stamp. The <code>Mutator</code> object
	 * must only be used to execute 1 mutation operation.
	 * @param poolName				The name of the connection pool to use (this determines the Cassandra database cluster)
	 * @return						A new <code>Mutator</code> object
	 */
	public static Mutator createMutator(String poolName) {
		return poolMap.get(poolName).createMutator();
	}

	/**
	 * Create a <code>Mutator</code> object with an arbitrary time stamp. The <code>Mutator</code> object
	 * must only be used to execute 1 mutation operation.
	 * @param poolName				The name of the connection pool to use (this determines the Cassandra database cluster)
	 * @param timestamp				The default time stamp to use for operations
	 * @return						A new <code>Mutator</code> object
	 */
	public static Mutator createMutator(String poolName, long timestamp) {
		return poolMap.get(poolName).createMutator(timestamp);
	}

	/**
	 * Create a <code>Mutator</code> object with an arbitrary time stamp. The <code>Mutator</code> object
	 * must only be used to execute 1 mutation operation.
	 * @param poolName				The name of the connection pool to use (this determines the Cassandra database cluster)
	 * @param timestamp				The default time stamp to use for operations
     * @param deleteIfNull If true the mutator will default to issuing deletes when it detects null values on a column
     *                      passed to the various write methods.
	 * @return						A new <code>Mutator</code> object
	 */
	public static Mutator createMutator(String poolName, long timestamp, boolean deleteIfNull) {
		return poolMap.get(poolName).createMutator(timestamp, deleteIfNull);
	}

	/**
	 * Create a {@link RowDeletor row deletor} object using the current time as the operation time stamp.
	 * @param poolName				The name of the connection pool to use (this determines the Cassandra database cluster)
	 * @return						A new {@link RowDeletor row deletor} object
	 */
	public static RowDeletor createRowDeletor(String poolName) {
		return poolMap.get(poolName).createRowDeletor();
	}

	/**
	 * Create a {@link RowDeletor row deletor} object with an arbitrary time stamp.
	 * @param poolName				The name of the connection pool to use (this determines the Cassandra database cluster)
	 * @param timestamp				The default time stamp to use for operations
     * @return						A new {@link RowDeletor row deletor} object
	 */
	public static RowDeletor createRowDeletor(String poolName, long timestamp) {
		return poolMap.get(poolName).createRowDeletor(timestamp);
	}

    /**
     * Create a <code>ClusterManager</code> object for use managing the cluster. For example, querying
     * the version of the Cassandra software, or the cluster name.
     * @param cluster
     * @return
     */
    public static ClusterManager createClusterManager(Cluster cluster) {
    	return new ClusterManager(cluster);
    }

    /**
     * Create a <code>KeyspaceManager</code> object for use managing keyspaces in the cluster. For example,
     * querying the list of keyspaces, or adding a new keyspace.
     * @param cluster
     * @return
     */
    public static KeyspaceManager createKeyspaceManager(Cluster cluster) {
    	return new KeyspaceManager(cluster);
    }

    /**
     * Create a <code>ColumnFamilyManager</code> object for use managing column families inside a keyspace. For
     * example, adding or removing a column family.
     * @param cluster
     * @param keyspace
     * @return
     */
    public static ColumnFamilyManager createColumnFamilyManager(Cluster cluster, String keyspace) {
    	return new ColumnFamilyManager(cluster, keyspace);
    }

	/**
	 * Get a direct reference to a DbConnPool. This should never be needed while using Pelops's <code>Mutator</code> and
	 * <code>Selector</code> in normal usage. The reason this function is provided, is so that the Pelops connection pooling system can be
	 * used in conjunction with other systems such as Lucandra.
	 * @param poolName				The name of the pool
	 * @return						A direct reference to the specified pool
	 */
	public static IThriftPool getDbConnPool(String poolName) {
		return poolMap.get(poolName);
	}
}

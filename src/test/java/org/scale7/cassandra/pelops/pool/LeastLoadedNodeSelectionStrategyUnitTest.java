package org.scale7.cassandra.pelops.pool;

import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.*;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

/**
 * Tests the {@link LeastLoadedNodeSelectionStrategy} class.
 */
public class LeastLoadedNodeSelectionStrategyUnitTest {
    /**
     * Test to verify that if all nodes are are suspended that null is returned.
     */
    @Test
    public void testAllNodesSuspended() {
        Set<String> nodeAddresses = new HashSet<String>(Arrays.asList("node1", "node2", "node3"));
        CommonsBackedPool pool = Mockito.mock(CommonsBackedPool.class);

        // setup each pooled node to report that it's suspended
        for (String nodeAddress : nodeAddresses) {
            prepGetPoolNode(pool, nodeAddress, new CommonsBackedPool.PooledNode(null, nodeAddress) {
                @Override
                public boolean isSuspended() {
                    return true;
                }
            });
        }

        LeastLoadedNodeSelectionStrategy strategy = new LeastLoadedNodeSelectionStrategy();

        CommonsBackedPool.PooledNode node = strategy.select(pool, nodeAddresses, null);

        assertNull("No nodes should have been returned", node);
    }

    /**
     * Test to verify that if all but one of the nodes are suspended that that node is returned.
     */
    @Test
    public void testOnlyOneCandidateNode() {
        Set<String> nodeAddresses = new HashSet<String>(Arrays.asList("node1", "node2", "node3"));
        CommonsBackedPool pool = Mockito.mock(CommonsBackedPool.class);

        // setup each pooled node to report that it's suspended
        for (String nodeAddress : nodeAddresses) {
            prepGetPoolNode(pool, nodeAddress, new CommonsBackedPool.PooledNode(null, nodeAddress) {
                @Override
                public boolean isSuspended() {
                    return true;
                }
            });
        }

        // setup one node to be good
        final String goodNodeAddress = "node4";
        nodeAddresses.add(goodNodeAddress);
        prepGetPoolNode(pool, goodNodeAddress, new CommonsBackedPool.PooledNode(null, goodNodeAddress) {
            @Override
            public boolean isSuspended() {
                return false;
            }

            @Override
            public int getNumActive() {
                return 1;
            }
        });

        LeastLoadedNodeSelectionStrategy strategy = new LeastLoadedNodeSelectionStrategy();

        CommonsBackedPool.PooledNode node = strategy.select(pool, nodeAddresses, null);

        assertNotNull("No nodes were returned from the pool", node);
        assertEquals("Wrong node returned from the pool", goodNodeAddress, node.getAddress());
    }

    /**
     * Test to verify that the node with the least active connections is selected.
     */
    @Test
    public void testLeastLoadedNodeSelected() {
        String leastLoadedNodeAddress = "node1";
        List<String> nodeAddresses = Arrays.asList(leastLoadedNodeAddress, "node2", "node3", "node4", "node5");
        CommonsBackedPool pool = Mockito.mock(CommonsBackedPool.class);

        // setup each pooled node to report it's number of active connections
        for (int i = 0; i < nodeAddresses.size(); i++) {
            final int numActive = i;
            prepGetPoolNode(pool, nodeAddresses.get(i), new CommonsBackedPool.PooledNode(null, nodeAddresses.get(i)) {
                @Override
                public boolean isSuspended() {
                    return false;
                }

                @Override
                public int getNumActive() {
                    return numActive;
                }
            });
        }

        LeastLoadedNodeSelectionStrategy strategy = new LeastLoadedNodeSelectionStrategy();

        CommonsBackedPool.PooledNode node = strategy.select(pool, new HashSet<String>(nodeAddresses), null);

        assertNotNull("No nodes were returned from the pool", node);
        assertEquals("Wrong node returned from the pool", leastLoadedNodeAddress, node.getAddress());
    }

    /**
     * Test to verify that the node with the least active connections is skipped because of the notNodeHint.
     */
    @Test
    public void testLeastLoadedNodeSkippedOnNotNodeHint() {
        String leastLoadedNodeAddress = "node1";
        String selectedNodeAddress = "node2";
        List<String> nodeAddresses = Arrays.asList(leastLoadedNodeAddress, selectedNodeAddress, "node3", "node4", "node5");
        CommonsBackedPool pool = Mockito.mock(CommonsBackedPool.class);

        // setup each pooled node to report it's number of active connections
        for (int i = 0; i < nodeAddresses.size(); i++) {
            final int numActive = i;
            prepGetPoolNode(pool, nodeAddresses.get(i), new CommonsBackedPool.PooledNode(null, nodeAddresses.get(i)) {
                @Override
                public boolean isSuspended() {
                    return false;
                }

                @Override
                public int getNumActive() {
                    return numActive;
                }
            });
        }

        LeastLoadedNodeSelectionStrategy strategy = new LeastLoadedNodeSelectionStrategy();

        CommonsBackedPool.PooledNode node = strategy.select(pool, new HashSet<String>(nodeAddresses), leastLoadedNodeAddress);

        assertNotNull("No nodes were returned from the pool", node);
        assertEquals("Wrong node returned from the pool", selectedNodeAddress, node.getAddress());
    }

    private void prepGetPoolNode(CommonsBackedPool pool, final String nodeAddress, final CommonsBackedPool.PooledNode pooledNode) {
        Mockito.when(pool.getPooledNode(nodeAddress)).thenAnswer(new Answer<CommonsBackedPool.PooledNode>() {
            @Override
            public CommonsBackedPool.PooledNode answer(InvocationOnMock invocation) throws Throwable {
                return pooledNode;
            }
        });
    }
}

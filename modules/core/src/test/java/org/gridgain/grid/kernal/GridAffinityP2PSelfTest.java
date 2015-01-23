/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gridgain.grid.kernal;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.kernal.processors.cache.distributed.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.config.*;
import org.gridgain.testframework.junits.common.*;

import java.net.*;
import java.util.*;

import static org.gridgain.grid.cache.GridCacheMode.*;

/**
 * Tests affinity and affinity mapper P2P loading.
 */
public class GridAffinityP2PSelfTest extends GridCommonAbstractTest {
    /** VM ip finder for TCP discovery. */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    private static final String EXT_AFFINITY_MAPPER_CLS_NAME = "org.gridgain.grid.tests.p2p.GridExternalAffinityMapper";

    /** */
    private static final String EXT_AFFINITY_CLS_NAME = "org.gridgain.grid.tests.p2p.GridExternalAffinity";

    /** URL of classes. */
    private static final URL[] URLS;

    /** Current deployment mode. Used in {@link #getConfiguration(String)}. */
    private IgniteDeploymentMode depMode;

    /**
     * Initialize URLs.
     */
    static {
        try {
            URLS = new URL[] {new URL(GridTestProperties.getProperty("p2p.uri.cls"))};
        }
        catch (MalformedURLException e) {
            throw new RuntimeException("Define property p2p.uri.cls", e);
        }
    }

    /**
     *
     */
    public GridAffinityP2PSelfTest() {
        super(false);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setMaxMissedHeartbeats(Integer.MAX_VALUE);
        disco.setIpFinder(ipFinder);

        c.setDiscoverySpi(disco);

        c.setDeploymentMode(depMode);

        if (gridName.endsWith("1"))
            c.setCacheConfiguration(); // Empty cache configuration.
        else {
            assert gridName.endsWith("2") || gridName.endsWith("3");

            CacheConfiguration cc = defaultCacheConfiguration();

            cc.setCacheMode(PARTITIONED);

            GridTestExternalClassLoader ldr = new GridTestExternalClassLoader(URLS);

            cc.setAffinity((GridCacheAffinityFunction)ldr.loadClass(EXT_AFFINITY_CLS_NAME).newInstance());
            cc.setAffinityMapper((GridCacheAffinityKeyMapper)ldr.loadClass(EXT_AFFINITY_MAPPER_CLS_NAME)
                .newInstance());

            c.setCacheConfiguration(cc);
            c.setUserAttributes(F.asMap(GridCacheModuloAffinityFunction.IDX_ATTR, gridName.endsWith("2") ? 0 : 1));
        }

        return c;
    }

    /**
     * Test {@link org.apache.ignite.configuration.IgniteDeploymentMode#PRIVATE} mode.
     *
     * @throws Exception if error occur.
     */
    public void testPrivateMode() throws Exception {
        depMode = IgniteDeploymentMode.PRIVATE;

        affinityTest();
    }

    /**
     * Test {@link org.apache.ignite.configuration.IgniteDeploymentMode#ISOLATED} mode.
     *
     * @throws Exception if error occur.
     */
    public void testIsolatedMode() throws Exception {
        depMode = IgniteDeploymentMode.ISOLATED;

        affinityTest();
    }

    /**
     * Test {@link org.apache.ignite.configuration.IgniteDeploymentMode#CONTINUOUS} mode.
     *
     * @throws Exception if error occur.
     */
    public void testContinuousMode() throws Exception {
        depMode = IgniteDeploymentMode.CONTINUOUS;

        affinityTest();
    }

    /**
     * Test {@link org.apache.ignite.configuration.IgniteDeploymentMode#SHARED} mode.
     *
     * @throws Exception if error occur.
     */
    public void testSharedMode() throws Exception {
        depMode = IgniteDeploymentMode.SHARED;

        affinityTest();
    }

    /** @throws Exception If failed. */
    private void affinityTest() throws Exception {
        Ignite g1 = startGrid(1);
        Ignite g2 = startGrid(2);
        Ignite g3 = startGrid(3);

        try {
            assert g1.configuration().getCacheConfiguration().length == 0;
            assert g2.configuration().getCacheConfiguration()[0].getCacheMode() == PARTITIONED;
            assert g3.configuration().getCacheConfiguration()[0].getCacheMode() == PARTITIONED;

            ClusterNode first = g2.cluster().localNode();
            ClusterNode second = g3.cluster().localNode();

            //When external affinity and mapper are set to cache configuration we expect the following.
            //Key 0 is mapped to partition 0, first node.
            //Key 1 is mapped to partition 1, second node.
            //key 2 is mapped to partition 0, first node because mapper substitutes key 2 with affinity key 0.
            Map<ClusterNode, Collection<Integer>> map = g1.cluster().mapKeysToNodes(null, F.asList(0));

            assertNotNull(map);
            assertEquals("Invalid map size: " + map.size(), 1, map.size());
            assertEquals(F.first(map.keySet()), first);

            ClusterNode n1 = g1.cluster().mapKeyToNode(null, 1);

            assertNotNull(n1);

            UUID id1 = n1.id();

            assertNotNull(id1);
            assertEquals(second.id(), id1);

            ClusterNode n2 = g1.cluster().mapKeyToNode(null, 2);

            assertNotNull(n2);

            UUID id2 = n2.id();

            assertNotNull(id2);
            assertEquals(first.id(), id2);
        }
        finally {
            stopGrid(1);
            stopGrid(2);
            stopGrid(3);
        }
    }
}

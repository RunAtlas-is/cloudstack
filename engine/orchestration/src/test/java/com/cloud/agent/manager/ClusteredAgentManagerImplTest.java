// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.agent.manager;

import com.cloud.cluster.ClusterManager;
import com.cloud.cluster.agentlb.dao.HostTransferMapDao;
import com.cloud.configuration.ManagementServiceConfiguration;
import com.cloud.ha.HighAvailabilityManagerImpl;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceManagerImpl;
import org.apache.cloudstack.ha.dao.HAConfigDao;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.outofbandmanagement.dao.OutOfBandManagementDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ClusteredAgentManagerImplTest {

    private HostDao _hostDao;
    @Mock
    ManagementServiceConfiguration _mgmtServiceConf;

    @Before
    public void setUp() throws Exception {
        _hostDao = mock(HostDao.class);
    }

    @Test
    public void scanDirectAgentToLoadNoHostsTest() {
        ClusteredAgentManagerImpl clusteredAgentManagerImpl = mock(ClusteredAgentManagerImpl.class);
        clusteredAgentManagerImpl._hostDao = _hostDao;
        clusteredAgentManagerImpl.scanDirectAgentToLoad();
        verify(clusteredAgentManagerImpl, never()).findAttache(anyLong());
        verify(clusteredAgentManagerImpl, never()).loadDirectlyConnectedHost(any(), anyBoolean());
    }

    @Test
    public void scanDirectAgentToLoadHostWithoutAttacheTest() {
        // Arrange
        ClusteredAgentManagerImpl clusteredAgentManagerImpl = Mockito.spy(ClusteredAgentManagerImpl.class);
        HostVO hostVO = mock(HostVO.class);
        clusteredAgentManagerImpl._hostDao = _hostDao;
        clusteredAgentManagerImpl.mgmtServiceConf = _mgmtServiceConf;
        clusteredAgentManagerImpl._resourceMgr = mock(ResourceManagerImpl.class);
        when(_mgmtServiceConf.getTimeout()).thenReturn(16000L);
        when(hostVO.getId()).thenReturn(1L);
        List hosts = new ArrayList<>();
        hosts.add(hostVO);
        when(_hostDao.findAndUpdateDirectAgentToLoad(anyLong(), anyLong(), anyLong())).thenReturn(hosts);
        AgentAttache agentAttache = mock(AgentAttache.class);
        doReturn(Boolean.TRUE).when(clusteredAgentManagerImpl).loadDirectlyConnectedHost(hostVO, false);
        clusteredAgentManagerImpl.scanDirectAgentToLoad();
        verify(clusteredAgentManagerImpl).loadDirectlyConnectedHost(hostVO, false);
    }

    @Test
    public void scanDirectAgentToLoadHostWithForwardAttacheTest() {
        ClusteredAgentManagerImpl clusteredAgentManagerImpl = Mockito.spy(ClusteredAgentManagerImpl.class);
        HostVO hostVO = mock(HostVO.class);
        clusteredAgentManagerImpl._hostDao = _hostDao;
        clusteredAgentManagerImpl.mgmtServiceConf = _mgmtServiceConf;
        when(_mgmtServiceConf.getTimeout()).thenReturn(16000L);
        when(hostVO.getId()).thenReturn(1L);
        List hosts = new ArrayList<>();
        hosts.add(hostVO);
        when(_hostDao.findAndUpdateDirectAgentToLoad(anyLong(), anyLong(), anyLong())).thenReturn(hosts);
        AgentAttache agentAttache = mock(AgentAttache.class);
        when(agentAttache.forForward()).thenReturn(Boolean.TRUE);
        when(clusteredAgentManagerImpl.findAttache(1L)).thenReturn(agentAttache);

        clusteredAgentManagerImpl.scanDirectAgentToLoad();
        verify(clusteredAgentManagerImpl).removeAgent(agentAttache, Status.Disconnected);
    }

    @Test
    public void scanDirectAgentToLoadHostWithNonForwardAttacheTest() {
        // Arrange
        ClusteredAgentManagerImpl clusteredAgentManagerImpl = Mockito.spy(new ClusteredAgentManagerImpl());
        HostVO hostVO = mock(HostVO.class);
        clusteredAgentManagerImpl._hostDao = _hostDao;
        clusteredAgentManagerImpl.mgmtServiceConf = _mgmtServiceConf;
        clusteredAgentManagerImpl._haMgr = mock(HighAvailabilityManagerImpl.class);
        when(_mgmtServiceConf.getTimeout()).thenReturn(16000L);
        when(hostVO.getId()).thenReturn(0L);
        List hosts = new ArrayList<>();
        hosts.add(hostVO);
        when(_hostDao.findAndUpdateDirectAgentToLoad(anyLong(), anyLong(), anyLong())).thenReturn(hosts);

        AgentAttache agentAttache = mock(AgentAttache.class);
        when(agentAttache.forForward()).thenReturn(Boolean.FALSE);
        when(clusteredAgentManagerImpl.findAttache(0L)).thenReturn(agentAttache);
        doReturn(Boolean.TRUE).when(clusteredAgentManagerImpl).agentStatusTransitTo(hostVO, Status.Event.Ping, clusteredAgentManagerImpl._nodeId);
        doReturn(Status.Up).when(clusteredAgentManagerImpl).investigate(agentAttache);

        clusteredAgentManagerImpl.scanDirectAgentToLoad();
        verify(clusteredAgentManagerImpl).investigate(agentAttache);
        verify(clusteredAgentManagerImpl).agentStatusTransitTo(hostVO, Status.Event.Ping, clusteredAgentManagerImpl._nodeId);
    }

    @Test
    public void scanDirectAgentToLoadHostWithNonForwardAttacheAndDisconnectedTest() {
        ClusteredAgentManagerImpl clusteredAgentManagerImpl = Mockito.spy(ClusteredAgentManagerImpl.class);
        HostVO hostVO = mock(HostVO.class);
        clusteredAgentManagerImpl._hostDao = _hostDao;
        clusteredAgentManagerImpl.mgmtServiceConf = _mgmtServiceConf;
        clusteredAgentManagerImpl._haMgr = mock(HighAvailabilityManagerImpl.class);
        clusteredAgentManagerImpl._resourceMgr = mock(ResourceManagerImpl.class);
        when(_mgmtServiceConf.getTimeout()).thenReturn(16000L);
        when(hostVO.getId()).thenReturn(0L);
        List hosts = new ArrayList<>();
        hosts.add(hostVO);
        when(_hostDao.findAndUpdateDirectAgentToLoad(anyLong(), anyLong(), anyLong())).thenReturn(hosts);
        AgentAttache agentAttache = mock(AgentAttache.class);
        when(agentAttache.forForward()).thenReturn(Boolean.FALSE);
        when(clusteredAgentManagerImpl.findAttache(0L)).thenReturn(agentAttache);
        doReturn(Boolean.TRUE).when(clusteredAgentManagerImpl).loadDirectlyConnectedHost(hostVO, false);
        clusteredAgentManagerImpl.scanDirectAgentToLoad();
        verify(clusteredAgentManagerImpl).investigate(agentAttache);
        verify(clusteredAgentManagerImpl).loadDirectlyConnectedHost(hostVO, false);
    }

    private static final String PEER = "42";

    private ServerSocketChannel listen() throws IOException {
        return ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /** A connected pair: [0] the non-blocking side a management server caches, [1] the peer's end. */
    private SocketChannel[] connect(final ServerSocketChannel server) throws IOException {
        final SocketChannel cached = SocketChannel.open(server.getLocalAddress());
        final SocketChannel peerEnd = server.accept();
        cached.configureBlocking(false);
        return new SocketChannel[] {cached, peerEnd};
    }

    private boolean seenClosedWithin(final SocketChannel ch, final long millis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (ClusteredAgentManagerImpl.isClosedByPeer(ch)) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private ClusteredAgentManagerImpl managerCaching(final SocketChannel ch) {
        final ClusteredAgentManagerImpl manager = Mockito.spy(ClusteredAgentManagerImpl.class);
        manager._peers = new HashMap<>();
        manager._sslEngines = new HashMap<>();
        manager._peers.put(PEER, ch);
        return manager;
    }

    private ManagementServerHost peerNode() {
        final ManagementServerHost node = mock(ManagementServerHost.class);
        when(node.getMsid()).thenReturn(Long.parseLong(PEER));
        return node;
    }

    @Test
    public void isClosedByPeerIsFalseWhileThePeerKeepsTheChannel() throws IOException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            assertFalse(ClusteredAgentManagerImpl.isClosedByPeer(pair[0]));
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    public void isClosedByPeerDrainsUnreadBytesFromAnOpenPeer() throws IOException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            pair[1].write(ByteBuffer.wrap("post-handshake record".getBytes(StandardCharsets.UTF_8)));
            assertFalse(ClusteredAgentManagerImpl.isClosedByPeer(pair[0]));
            assertFalse(ClusteredAgentManagerImpl.isClosedByPeer(pair[0]));
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    public void isClosedByPeerIsTrueOnceThePeerProcessCloses() throws IOException, InterruptedException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            pair[1].write(ByteBuffer.wrap("close_notify".getBytes(StandardCharsets.UTF_8)));
            pair[1].close();
            assertTrue(seenClosedWithin(pair[0], 5000));
            pair[0].close();
        }
    }

    @Test
    public void isClosedByPeerIsTrueForAChannelClosedHere() throws IOException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            pair[0].close();
            assertTrue(ClusteredAgentManagerImpl.isClosedByPeer(pair[0]));
            pair[1].close();
        }
    }

    @Test(timeout = 10000)
    public void isClosedByPeerNeverReadsABlockingChannel() throws IOException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            pair[0].configureBlocking(true);
            assertFalse(ClusteredAgentManagerImpl.isClosedByPeer(pair[0]));
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    public void connectToPeerKeepsACachedChannelThePeerHolds() throws IOException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            final ClusteredAgentManagerImpl manager = managerCaching(pair[0]);
            manager._clusterMgr = mock(ClusterManager.class);

            assertSame(pair[0], manager.connectToPeer(PEER, null));
            verify(manager._clusterMgr, never()).getPeer(any());
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    public void connectToPeerReplacesACachedChannelThePeerClosed() throws IOException, InterruptedException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            pair[1].close();
            assertTrue(seenClosedWithin(pair[0], 5000));
            final ClusteredAgentManagerImpl manager = managerCaching(pair[0]);
            manager._clusterMgr = mock(ClusterManager.class);
            when(manager._clusterMgr.getPeer(PEER)).thenReturn(null);

            assertNull(manager.connectToPeer(PEER, null));
            verify(manager._clusterMgr).getPeer(PEER);
            assertFalse(manager._peers.containsKey(PEER));
            assertFalse(pair[0].isOpen());
        }
    }

    @Test
    public void onManagementNodeJoinedClosesTheChannelToThePeersPreviousProcess() throws IOException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            final ClusteredAgentManagerImpl manager = managerCaching(pair[0]);

            manager.onManagementNodeJoined(Collections.singletonList(peerNode()), 1L);

            assertFalse(manager._peers.containsKey(PEER));
            assertFalse(pair[0].isOpen());
            pair[1].close();
        }
    }

    @Test
    public void onManagementNodeLeftClosesTheChannelToThePeer() throws IOException {
        try (ServerSocketChannel server = listen()) {
            final SocketChannel[] pair = connect(server);
            final ClusteredAgentManagerImpl manager = managerCaching(pair[0]);
            manager._hostDao = _hostDao;
            manager.mgmtServiceConf = _mgmtServiceConf;
            manager._hostTransferDao = mock(HostTransferMapDao.class);
            ReflectionTestUtils.setField(manager, "outOfBandManagementDao", mock(OutOfBandManagementDao.class));
            ReflectionTestUtils.setField(manager, "haConfigDao", mock(HAConfigDao.class));

            manager.onManagementNodeLeft(Collections.singletonList(peerNode()), 1L);

            assertFalse(manager._peers.containsKey(PEER));
            assertFalse(pair[0].isOpen());
            pair[1].close();
        }
    }
}

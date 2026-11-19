/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.ha;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FenceAnswer;
import com.cloud.agent.api.FenceCommand;
import com.cloud.alert.AlertManager;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.ha.HAManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class KVMFencerTest {

    @Mock
    HostDao hostDao;
    @Mock
    AgentManager agentManager;
    @Mock
    AlertManager alertMgr;
    @Mock
    ResourceManager resourceManager;
    @Mock
    HAManager haManager;
    @Mock
    PrimaryDataStoreDao storagePoolDao;

    KVMFencer fencer;

    @Before
    public void setup() throws ReflectiveOperationException {
        // the fencing tests below the guard tests describe fencing where the setting allows the RBD heartbeat
        setRbdHeartbeatWithoutHostHa(true);
        fencer = new KVMFencer();
        fencer._agentMgr = agentManager;
        fencer._alertMgr = alertMgr;
        fencer._hostDao = hostDao;
        fencer._resourceMgr = resourceManager;
        fencer.haManager = haManager;
        fencer.storagePoolDao = storagePoolDao;
    }

    @After
    public void restoreRbdHeartbeatSetting() throws ReflectiveOperationException {
        setRbdHeartbeatWithoutHostHa(false);
    }

    private void setRbdHeartbeatWithoutHostHa(boolean allowed) throws ReflectiveOperationException {
        Field defaultValue = ConfigKey.class.getDeclaredField("_defaultValue");
        defaultValue.setAccessible(true);
        defaultValue.set(HighAvailabilityManager.KvmHaRbdHeartbeatWithoutHostHa, String.valueOf(allowed));
    }

    private HostVO kvmHostInCluster() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getClusterId()).thenReturn(1L);
        Mockito.when(host.getDataCenterId()).thenReturn(1L);
        Mockito.lenient().when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        return host;
    }

    private StoragePoolVO pool(StoragePoolType type) {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getPoolType()).thenReturn(type);
        return pool;
    }

    private void poolsOfHost(List<StoragePoolVO> clusterPools) {
        Mockito.when(storagePoolDao.findPoolsInClusters(Collections.singletonList(1L), null)).thenReturn(clusterPools);
        Mockito.when(storagePoolDao.findZoneWideStoragePoolsByHypervisor(1L, HypervisorType.KVM)).thenReturn(Collections.emptyList());
    }

    @Test
    public void testHeartbeatFencingIsAllowedWhenTheSettingAllowsIt() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getDataCenterId()).thenReturn(1L);
        Assert.assertTrue(fencer.canFenceByHeartbeat(host));
        Mockito.verifyNoInteractions(haManager, storagePoolDao);
    }

    @Test
    public void testRbdOnlyHostWithoutHostHaIsNotFenced() throws ReflectiveOperationException {
        setRbdHeartbeatWithoutHostHa(false);
        HostVO host = kvmHostInCluster();
        Mockito.when(haManager.isHAEligible(host)).thenReturn(false);
        poolsOfHost(Collections.singletonList(pool(StoragePoolType.RBD)));
        VirtualMachine virtualMachine = Mockito.mock(VirtualMachine.class);

        Assert.assertNull(fencer.fenceOff(virtualMachine, host));
        Mockito.verifyNoInteractions(agentManager, resourceManager, alertMgr);
    }

    @Test
    public void testRbdOnlyHostWithHostHaCanBeFenced() throws ReflectiveOperationException {
        setRbdHeartbeatWithoutHostHa(false);
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getDataCenterId()).thenReturn(1L);
        Mockito.when(haManager.isHAEligible(host)).thenReturn(true);

        Assert.assertTrue(fencer.canFenceByHeartbeat(host));
        Mockito.verifyNoInteractions(storagePoolDao);
    }

    @Test
    public void testHostWithAnotherHeartbeatStorageCanBeFenced() throws ReflectiveOperationException {
        setRbdHeartbeatWithoutHostHa(false);
        HostVO host = kvmHostInCluster();
        Mockito.when(haManager.isHAEligible(host)).thenReturn(false);
        poolsOfHost(Arrays.asList(pool(StoragePoolType.RBD), pool(StoragePoolType.NetworkFilesystem)));

        Assert.assertTrue(fencer.canFenceByHeartbeat(host));
    }

    @Test
    public void testWithSingleHost() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getClusterId()).thenReturn(1l);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getDataCenterId()).thenReturn(1l);
        Mockito.when(host.getPodId()).thenReturn(1l);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);
        Mockito.when(host.getId()).thenReturn(1l);
        VirtualMachine virtualMachine = Mockito.mock(VirtualMachine.class);

        Mockito.when(resourceManager.listAllHostsInCluster(1l)).thenReturn(Collections.singletonList(host));
        Assert.assertFalse(fencer.fenceOff(virtualMachine, host));
    }

    @Test
    public void testWithSingleHostDown() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getClusterId()).thenReturn(1l);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getDataCenterId()).thenReturn(1l);
        Mockito.when(host.getPodId()).thenReturn(1l);
        Mockito.when(host.getStatus()).thenReturn(Status.Down);
        Mockito.when(host.getId()).thenReturn(1l);
        VirtualMachine virtualMachine = Mockito.mock(VirtualMachine.class);

        Mockito.when(resourceManager.listAllHostsInCluster(1l)).thenReturn(Collections.singletonList(host));
        Assert.assertFalse(fencer.fenceOff(virtualMachine, host));
    }

    @Test
    public void testWithHosts() throws AgentUnavailableException, OperationTimedoutException {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getClusterId()).thenReturn(1l);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);
        Mockito.lenient().when(host.getDataCenterId()).thenReturn(1l);
        Mockito.lenient().when(host.getPodId()).thenReturn(1l);
        Mockito.when(host.getId()).thenReturn(1l);

        HostVO secondHost = Mockito.mock(HostVO.class);
        Mockito.lenient().when(secondHost.getClusterId()).thenReturn(1l);
        Mockito.when(secondHost.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(secondHost.getStatus()).thenReturn(Status.Up);
        Mockito.lenient().when(secondHost.getDataCenterId()).thenReturn(1l);
        Mockito.lenient().when(secondHost.getPodId()).thenReturn(1l);
        Mockito.when(host.getId()).thenReturn(2l);

        VirtualMachine virtualMachine = Mockito.mock(VirtualMachine.class);

        Mockito.when(resourceManager.listAllHostsInCluster(1l)).thenReturn(Arrays.asList(host, secondHost));

        FenceAnswer answer = new FenceAnswer(null, true, "ok");
        Mockito.when(agentManager.send(ArgumentMatchers.anyLong(), ArgumentMatchers.any(FenceCommand.class))).thenReturn(answer);

        Assert.assertTrue(fencer.fenceOff(virtualMachine, host));
    }

    @Test
    public void testWithFailingFence() throws AgentUnavailableException, OperationTimedoutException {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getClusterId()).thenReturn(1l);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);
        Mockito.when(host.getDataCenterId()).thenReturn(1l);
        Mockito.when(host.getPodId()).thenReturn(1l);
        Mockito.when(host.getId()).thenReturn(1l);

        HostVO secondHost = Mockito.mock(HostVO.class);
        Mockito.lenient().when(secondHost.getClusterId()).thenReturn(1l);
        Mockito.when(secondHost.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(secondHost.getStatus()).thenReturn(Status.Up);
        Mockito.lenient().when(secondHost.getDataCenterId()).thenReturn(1l);
        Mockito.lenient().when(secondHost.getPodId()).thenReturn(1l);
        Mockito.when(host.getId()).thenReturn(2l);

        VirtualMachine virtualMachine = Mockito.mock(VirtualMachine.class);

        Mockito.when(resourceManager.listAllHostsInCluster(1l)).thenReturn(Arrays.asList(host, secondHost));

        Mockito.when(agentManager.send(ArgumentMatchers.anyLong(), ArgumentMatchers.any(FenceCommand.class))).thenThrow(new AgentUnavailableException(2l));

        Assert.assertFalse(fencer.fenceOff(virtualMachine, host));
    }

    @Test
    public void testWithTimeoutingFence() throws AgentUnavailableException, OperationTimedoutException {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getClusterId()).thenReturn(1l);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);
        Mockito.when(host.getDataCenterId()).thenReturn(1l);
        Mockito.when(host.getPodId()).thenReturn(1l);
        Mockito.when(host.getId()).thenReturn(1l);

        HostVO secondHost = Mockito.mock(HostVO.class);
        Mockito.lenient().when(secondHost.getClusterId()).thenReturn(1l);
        Mockito.when(secondHost.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(secondHost.getStatus()).thenReturn(Status.Up);
        Mockito.lenient().when(secondHost.getDataCenterId()).thenReturn(1l);
        Mockito.lenient().when(secondHost.getPodId()).thenReturn(1l);
        Mockito.when(host.getId()).thenReturn(2l);

        VirtualMachine virtualMachine = Mockito.mock(VirtualMachine.class);

        Mockito.when(resourceManager.listAllHostsInCluster(1l)).thenReturn(Arrays.asList(host, secondHost));

        Mockito.when(agentManager.send(ArgumentMatchers.anyLong(), ArgumentMatchers.any(FenceCommand.class))).thenThrow(new OperationTimedoutException(null, 2l, 0l, 0, false));

        Assert.assertFalse(fencer.fenceOff(virtualMachine, host));
    }

    @Test
    public void testWithSingleNotKVM() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.lenient().when(host.getClusterId()).thenReturn(1l);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.Any);
        Mockito.lenient().when(host.getStatus()).thenReturn(Status.Down);
        Mockito.lenient().when(host.getId()).thenReturn(1l);
        Mockito.lenient().when(host.getDataCenterId()).thenReturn(1l);
        Mockito.lenient().when(host.getPodId()).thenReturn(1l);
        VirtualMachine virtualMachine = Mockito.mock(VirtualMachine.class);

        Mockito.lenient().when(resourceManager.listAllHostsInCluster(1l)).thenReturn(Collections.singletonList(host));
        Assert.assertNull(fencer.fenceOff(virtualMachine, host));
    }

}

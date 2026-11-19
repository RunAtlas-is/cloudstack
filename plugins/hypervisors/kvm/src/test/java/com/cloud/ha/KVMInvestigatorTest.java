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

import java.lang.reflect.Field;
import java.util.Collections;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.ha.HAManager;
import org.apache.cloudstack.kvm.ha.KVMHostActivityChecker;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;

@RunWith(MockitoJUnitRunner.class)
public class KVMInvestigatorTest {

    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private HAManager haManager;
    @Mock
    private DataStoreProviderManager dataStoreProviderMgr;
    @Mock
    private KVMHostActivityChecker hostActivityChecker;
    @Mock
    private Host host;
    @Mock
    private StoragePoolVO rbdPool;

    @InjectMocks
    private KVMInvestigator investigator;

    @Before
    public void setup() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getClusterId()).thenReturn(1L);
        Mockito.when(host.getDataCenterId()).thenReturn(1L);
        Mockito.when(haManager.isHAEligible(host)).thenReturn(false);
        Mockito.when(rbdPool.getPoolType()).thenReturn(StoragePoolType.RBD);
        Mockito.when(storagePoolDao.findPoolsInClusters(Collections.singletonList(1L), null)).thenReturn(Collections.singletonList(rbdPool));
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

    @Test
    public void testRbdHeartbeatIsUsedWithoutHostHaWhenTheSettingAllowsIt() throws ReflectiveOperationException {
        setRbdHeartbeatWithoutHostHa(true);
        Mockito.when(rbdPool.getStorageProviderName()).thenReturn("DefaultPrimary");
        DataStoreProvider provider = Mockito.mock(DataStoreProvider.class);
        PrimaryDataStoreDriver driver = Mockito.mock(PrimaryDataStoreDriver.class);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("DefaultPrimary")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(driver);
        Mockito.when(driver.isStorageSupportHA(StoragePoolType.RBD)).thenReturn(true);
        Mockito.when(hostActivityChecker.getHostAgentStatus(host)).thenReturn(Status.Down);

        Assert.assertEquals(Status.Down, investigator.getHostAgentStatus(host));
    }

    @Test
    public void testRbdHeartbeatIsNotUsedWithoutHostHaByDefault() {
        Mockito.when(storagePoolDao.findZoneWideStoragePoolsByHypervisor(1L, HypervisorType.KVM)).thenReturn(Collections.emptyList());

        Assert.assertNull(investigator.getHostAgentStatus(host));
        Mockito.verifyNoInteractions(hostActivityChecker, dataStoreProviderMgr);
    }
}

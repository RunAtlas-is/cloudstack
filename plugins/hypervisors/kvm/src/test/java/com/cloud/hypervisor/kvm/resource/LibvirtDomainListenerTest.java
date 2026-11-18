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
package com.cloud.hypervisor.kvm.resource;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.event.DomainEvent;
import org.libvirt.event.DomainEventType;
import org.libvirt.event.StoppedDetail;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.resource.AgentStatusUpdater;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtDomainListenerTest {

    @Mock
    private AgentStatusUpdater agentStatusUpdater;
    @Mock
    private LibvirtDomainListener.VmStopRecorder vmStopRecorder;
    @Mock
    private Domain domain;
    @Mock
    private DomainEvent domainEvent;

    @Test
    public void testGuestShutdownIsRecordedAndReported() throws LibvirtException {
        Mockito.when(domain.getName()).thenReturn("i-2-10-VM");
        Mockito.when(domainEvent.getType()).thenReturn(DomainEventType.STOPPED);
        Mockito.when(domainEvent.getDetail()).thenReturn(StoppedDetail.SHUTDOWN);

        new LibvirtDomainListener(agentStatusUpdater, vmStopRecorder).onLifecycleChange(domain, domainEvent);

        Mockito.verify(vmStopRecorder).stopped("i-2-10-VM", "SHUTDOWN");
        Mockito.verify(agentStatusUpdater).triggerUpdate();
    }

    @Test
    public void testDestroyIsRecordedWithoutAnEarlyReport() throws LibvirtException {
        Mockito.when(domain.getName()).thenReturn("i-2-10-VM");
        Mockito.when(domainEvent.getType()).thenReturn(DomainEventType.STOPPED);
        Mockito.when(domainEvent.getDetail()).thenReturn(StoppedDetail.DESTROYED);

        new LibvirtDomainListener(agentStatusUpdater, vmStopRecorder).onLifecycleChange(domain, domainEvent);

        Mockito.verify(vmStopRecorder).stopped("i-2-10-VM", "DESTROYED");
        Mockito.verifyNoInteractions(agentStatusUpdater);
    }

    @Test
    public void testStartForgetsTheLastStop() throws LibvirtException {
        Mockito.when(domain.getName()).thenReturn("i-2-10-VM");
        Mockito.when(domainEvent.getType()).thenReturn(DomainEventType.STARTED);

        new LibvirtDomainListener(agentStatusUpdater, vmStopRecorder).onLifecycleChange(domain, domainEvent);

        Mockito.verify(vmStopRecorder).started("i-2-10-VM");
        Mockito.verifyNoInteractions(agentStatusUpdater);
    }

    @Test
    public void testWithoutARecorderOnlyTheEarlyReportIsSent() throws LibvirtException {
        Mockito.when(domainEvent.getType()).thenReturn(DomainEventType.STOPPED);
        Mockito.when(domainEvent.getDetail()).thenReturn(StoppedDetail.CRASHED);

        new LibvirtDomainListener(agentStatusUpdater).onLifecycleChange(domain, domainEvent);

        Mockito.verify(agentStatusUpdater).triggerUpdate();
    }
}

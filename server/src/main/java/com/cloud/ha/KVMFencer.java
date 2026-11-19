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
package com.cloud.ha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.ha.HAManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.agent.AgentManager;
import com.cloud.alert.AlertManager;
import com.cloud.agent.api.FenceAnswer;
import com.cloud.agent.api.FenceCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VirtualMachine;

public class KVMFencer extends AdapterBase implements FenceBuilder {

    @Inject
    HostDao _hostDao;
    @Inject
    AgentManager _agentMgr;
    @Inject
    AlertManager _alertMgr;
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    HAManager haManager;
    @Inject
    PrimaryDataStoreDao storagePoolDao;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public boolean start() {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public boolean stop() {
        // TODO Auto-generated method stub
        return true;
    }

    public KVMFencer() {
        super();
    }

    @Override
    public Boolean fenceOff(VirtualMachine vm, Host host) {
        if (host.getHypervisorType() != HypervisorType.KVM && host.getHypervisorType() != HypervisorType.LXC) {
            logger.warn("Don't know how to fence non kvm hosts {}", host.getHypervisorType());
            return null;
        }

        if (!canFenceByHeartbeat(host)) {
            logger.warn("Not fencing off {}: Host HA is not enabled on it and its only heartbeat storage is Ceph RBD, which {} keeps out of VM HA",
                    host, HighAvailabilityManager.KvmHaRbdHeartbeatWithoutHostHa.key());
            return null;
        }

        List<HostVO> hosts = _resourceMgr.listAllHostsInCluster(host.getClusterId());
        FenceCommand fence = new FenceCommand(vm, host);
        fence.setReportCheckFailureIfOneStorageIsDown(HighAvailabilityManager.KvmHAFenceHostIfHeartbeatFailsOnStorage.value());

        int i = 0;
        for (HostVO h : hosts) {
            if (h.getHypervisorType() == HypervisorType.KVM || h.getHypervisorType() == HypervisorType.LXC) {
                if (h.getStatus() != Status.Up) {
                    continue;
                }

                i++;

                if (h.getId() == host.getId()) {
                    continue;
                }
                FenceAnswer answer;
                try {
                    answer = (FenceAnswer)_agentMgr.send(h.getId(), fence);
                } catch (AgentUnavailableException | OperationTimedoutException e) {
                    logger.info("Moving on to the next host because {} is unavailable", h.toString(), e);
                    continue;
                }
                if (answer != null && answer.getResult()) {
                    return true;
                }
            }
        }

        _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, host.getDataCenterId(), host.getPodId(),
                            "Unable to fence off host: " + host.getId(),
                            "Fencing off host " + host.getId() + " did not succeed after asking " + i + " hosts. " +
                            "Check Agent logs for more information.");

        logger.error("Unable to fence off {} on {}", vm.toString(), host.toString());

        return false;
    }

    /**
     * Without Host HA, the neighbours fence a host by its storage heartbeat alone. On Ceph RBD a host whose agent stopped
     * has a stale heartbeat while its VMs keep running, so that heartbeat is used only when kvm.ha.rbd.heartbeat.without.host.ha
     * allows it, or when the host also has a heartbeat on another storage type.
     */
    protected boolean canFenceByHeartbeat(Host host) {
        if (HighAvailabilityManager.KvmHaRbdHeartbeatWithoutHostHa.valueIn(host.getDataCenterId()) || haManager.isHAEligible(host)) {
            return true;
        }
        List<StoragePoolVO> pools = new ArrayList<>(storagePoolDao.findPoolsInClusters(Collections.singletonList(host.getClusterId()), null));
        pools.addAll(storagePoolDao.findZoneWideStoragePoolsByHypervisor(host.getDataCenterId(), host.getHypervisorType()));
        return pools.stream().anyMatch(pool -> pool.getPoolType() != StoragePoolType.RBD
                && HighAvailabilityManager.LIBVIRT_STORAGE_POOL_TYPES_WITH_HA_SUPPORT.contains(pool.getPoolType()));
    }
}

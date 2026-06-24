/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.testdiscovery.zeroconf;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.Integration.IntegrationDeviceBase;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * zeroconf 闭环的<b>设备验证器</b>——等待本集成的仿真设备加载并上报数据。
 *
 * <p><b>单一职责</b>：轮询本集成设备，确认 {@link TestDiscoverySimulatedDevice} 已加载、deviceStatus=NORMAL、
 * temperature 有值。不关心广播 / flow / 结果。
 *
 * <p><b>遍历用 {@code integration.getAllDevices()}</b>，<b>不能</b>用
 * {@code core.getDeviceRegistry().getDeviceByID(uniqueId)}——{@code DeviceBase.getId()} 返回 entryId(UUID) 非 uniqueId，
 * 按 uniqueId 查返回 null（见 memory devicebase-getid-returns-entryid）。
 *
 * @author coffee
 */
public class SimDeviceVerifier {

    private static final long POLL_INTERVAL_MS = 1000L;

    private final IntegrationDeviceBase integration;
    private final Log log = LogFactory.getLogger(getClass());

    public SimDeviceVerifier(IntegrationDeviceBase integration) {
        this.integration = integration;
    }

    /**
     * 等待仿真设备就绪（NORMAL + temperature 有值），最多 maxWaitSecs。
     *
     * @return true=设备在线且有数据；false=超时未就绪
     */
    public boolean waitForReady(int maxWaitSecs) {
        long deadline = System.currentTimeMillis() + maxWaitSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (DeviceBase d : integration.getAllDevices()) {
                if (d instanceof TestDiscoverySimulatedDevice) {
                    TestDiscoverySimulatedDevice sim = (TestDiscoverySimulatedDevice) d;
                    boolean online = sim.getDeviceStatus() == DeviceStatus.NORMAL;
                    boolean hasTemp = sim.getAttrs().get("temperature") != null
                            && sim.getAttrs().get("temperature").getValue() != null;
                    if (online && hasTemp) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}

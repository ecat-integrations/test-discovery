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

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.FloatAttribute;

import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 仿真设备（zeroconf 闭环的"设备运行"半边）。
 * <p>由 {@code TestDiscoveryIntegration.createDeviceFromEntry} 在 ZEROCONF 发现入网后构造；
 * {@code start()} 起周期轮询，生成仿真环境量（temperature/humidity/rssi）并发布，deviceStatus 置 NORMAL，
 * 完成"发现→入网→设备在线→数据上报"闭环。
 *
 * <p>纯内存仿真、无硬件依赖：每 {@link #POLL_INTERVAL_SEC} 秒生成 temperature≈25±5°C、humidity≈50±15%、
 * rssi≈-55±10dBm（带随机扰动），经 {@link #publicAttrsState()} 发布到 DEVICE_DATA_UPDATE 总线。
 *
 * @author coffee
 */
public class TestDiscoverySimulatedDevice extends DeviceBase {

    private static final long POLL_INTERVAL_SEC = 5L;

    private final Random rnd = new Random();

    private FloatAttribute temperatureAttr;
    private FloatAttribute humidityAttr;
    private FloatAttribute rssiAttr;
    private ScheduledFuture<?> pollTask;

    public TestDiscoverySimulatedDevice(ConfigEntry entry) {
        super(entry); // 存储 entry/config，提取 name/sn/model/vendor
    }

    @Override
    public void init() {
        // 仿真环境量属性（单位留空：测试 sim，无需单位语义）
        temperatureAttr = new FloatAttribute("temperature", AttributeClass.TEMPERATURE, null, null, 2, false, false);
        humidityAttr = new FloatAttribute("humidity", AttributeClass.HUMIDITY, null, null, 2, false, false);
        rssiAttr = new FloatAttribute("rssi", AttributeClass.VALUE, null, null, 1, false, false);
        setAttribute(temperatureAttr);
        setAttribute(humidityAttr);
        setAttribute(rssiAttr);
        log.info("[test-discovery-device] init: name={}, sn={}, model={}", getName(), getSn(), getModel());
    }

    @Override
    public void start() {
        // 幂等：createEntry() 已 start 一次，IntegrationDeviceBase.onStart() 会再 start 一次；
        // 防止重复调度出两个轮询任务（每次 start 都 scheduleAtFixedRate 会泄漏）。
        if (pollTask != null) {
            return;
        }
        // 直接字段赋值（DeviceBase.deviceStatus 为 protected）；自管理状态须 override computeDeviceStatus
        this.deviceStatus = DeviceStatus.NORMAL;
        log.info("[test-discovery-device] start: 仿真轮询已启动，间隔 {}s", POLL_INTERVAL_SEC);
        pollTask = getScheduledExecutor().scheduleAtFixedRate(
                this::generateData, 0, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** 生成一轮仿真数据并发布。 */
    private void generateData() {
        try {
            float temp = 25f + (rnd.nextFloat() * 10f - 5f);      // 20~30°C
            float hum = 50f + (rnd.nextFloat() * 30f - 15f);      // 35~65%
            float rssi = -55f + (rnd.nextFloat() * 20f - 10f);    // -65~-45 dBm
            temperatureAttr.updateValue(temp, AttributeStatus.NORMAL);
            humidityAttr.updateValue(hum, AttributeStatus.NORMAL);
            rssiAttr.updateValue(rssi, AttributeStatus.NORMAL);
            publicAttrsState(); // 发布到 DEVICE_DATA_UPDATE 总线（前端/订阅者可见）
            this.deviceStatus = DeviceStatus.NORMAL;
            log.debug("[test-discovery-device] 仿真数据: temp=" + temp + " hum=" + hum + " rssi=" + rssi);
        } catch (Exception e) {
            log.error("[test-discovery-device] 仿真轮询异常", e);
        }
    }

    @Override
    public void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        this.deviceStatus = DeviceStatus.OFFLINE;
    }

    @Override
    public void release() {
        stop();
    }

    /** 自管理 deviceStatus 字段，须 override 防止基类扫描逻辑覆盖。 */
    @Override
    protected DeviceStatus computeDeviceStatus() {
        return this.deviceStatus;
    }
}

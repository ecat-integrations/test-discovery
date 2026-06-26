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

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 仿真设备（zeroconf 闭环的"设备运行"半边）——用入网凭证访问 {@link ProbeHttpServer} 拉取数据上报。
 * <p>由 {@code TestDiscoveryIntegration.createDeviceFromEntry} 在发现入网后构造；{@code start()} 从 entry
 * 读 account/password/ip/port，起周期轮询带 HTTP Basic Auth 真连 {@code GET /data}，解析
 * temperature/humidity/rssi 并发布，deviceStatus 置 NORMAL——完成"发现→凭证→入网→凭证拉数据"真实闭环。
 *
 * <p>拉取失败（401 凭证无效 / 不可达 / 解析失败）→ deviceStatus 置 OFFLINE（不静默兜底、不编造数据）。
 * entry 缺凭证/地址（异常）→ 不起轮询、保持 OFFLINE 并日志告警。
 *
 * @author coffee
 */
public class TestDiscoverySimulatedDevice extends DeviceBase {

    private static final long POLL_INTERVAL_SEC = 5L;

    private FloatAttribute temperatureAttr;
    private FloatAttribute humidityAttr;
    private FloatAttribute rssiAttr;
    private ScheduledFuture<?> pollTask;

    /** 访问 /data 的账号/密码/地址——start() 从 entry 读，缺失则不起轮询（OFFLINE）。*/
    private String account;
    private String password;
    private String ip;
    private int port;

    public TestDiscoverySimulatedDevice(ConfigEntry entry) {
        super(entry); // 存储 entry/config，提取 name/sn/model/vendor；凭证在 start() 读
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
        // 从 entry 读访问凭证/地址（凭证由 flow 写入 entryData→entry.data；config 即 entry.getData()）
        this.account = (String) config.get("account");
        this.password = (String) config.get("password");
        this.ip = (String) config.get("ip");
        Object portObj = config.get("port");
        if (account == null || password == null || ip == null || !(portObj instanceof Number)) {
            // 入网凭证/地址缺失属异常（flow 应已写入），不起轮询、保持 OFFLINE 并明确告警（不兜底）
            log.error("[test-discovery-device] 入网凭证/地址缺失，无法拉取数据: account={}, ip={}, port={} (entry uniqueId={})",
                    account, ip, portObj, getEntry().getUniqueId());
            this.deviceStatus = DeviceStatus.OFFLINE;
            return;
        }
        this.port = ((Number) portObj).intValue();
        // 直接字段赋值（DeviceBase.deviceStatus 为 protected）；自管理状态须 override computeDeviceStatus
        this.deviceStatus = DeviceStatus.NORMAL;
        log.info("[test-discovery-device] start: 凭证拉取轮询已启动 {}s/次, ip={}:{}", POLL_INTERVAL_SEC, ip, port);
        pollTask = getScheduledExecutor().scheduleAtFixedRate(
                this::pullDataFromService, 0, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** 周期凭证拉取 /data：成功→解析上报 + NORMAL；失败(401/不可达/解析失败)→OFFLINE。 */
    private void pullDataFromService() {
        ProbeHttpClient.SensorData data = ProbeHttpClient.pullData(ip, port, account, password);
        if (data == null) {
            // 凭证无效 / 不可达 / 解析失败 → 设备离线（不静默兜底、不编造数据）
            this.deviceStatus = DeviceStatus.OFFLINE;
            log.warn("[test-discovery-device] 拉取 /data 失败（凭证无效或不可达），设备置 OFFLINE: ip={}:{}", ip, port);
            return;
        }
        temperatureAttr.updateValue(data.temperature, AttributeStatus.NORMAL);
        humidityAttr.updateValue(data.humidity, AttributeStatus.NORMAL);
        rssiAttr.updateValue(data.rssi, AttributeStatus.NORMAL);
        publicAttrsState(); // 发布到 DEVICE_DATA_UPDATE 总线（前端/订阅者可见）
        this.deviceStatus = DeviceStatus.NORMAL;
        log.debug("[test-discovery-device] 凭证拉取数据: temp={} hum={} rssi={}",
                data.temperature, data.humidity, data.rssi);
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

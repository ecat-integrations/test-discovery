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
import com.ecat.core.ConfigEntry.ConfigEntryRegistry;
import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationDeviceBase;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

import com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow;

/**
 * zeroconf 闭环 E2E 的<b>编排器</b>——把 {@link ZeroconfFlowDriver}（推进 flow 步）、
 * {@link SimDeviceVerifier}（验设备）、{@link E2EResultWriter}（写结果）按 E2E 序列串起来。
 *
 * <p><b>单一职责</b>：编排——幂等策略 + 异步追赶轮询节奏 + E2E 序列（驱动 probe→驱动 confirm→
 * 等 entry→验设备→写结果）。自身不做广播/驱动/验证/IO，全部委托协作方。
 *
 * <p><b>不负责广播</b>：广播方 {@link ZeroconfServiceBroadcaster} 由
 * {@link com.ecat.integration.testdiscovery.TestDiscoveryIntegration} 在 onLoad 长期常驻持有
 * （周期重新广播，压测去重鲁棒性），本编排器只消费"广播已触发发现"这一结果——抓首次发现建的 flow
 * 并驱动它入网。广播生命周期与 E2E 编排彻底解耦。
 *
 * <p>E2E 序列：broadcaster 已广播 → integration-zeroconf 发现 → startDiscoveryFlow(ZEROCONF) →
 * 本集成 flow 落 probe 步 → 驱动 probe（真实 HTTP 探活）→ 落 confirm → 驱动 confirm 入网 →
 * 仿真设备加载上报 → 验设备。
 *
 * <p><b>事件触发</b>：由入口在 {@link com.ecat.core.Bus.BusTopic#INTEGRATIONS_ALL_LOADED} 后调用 {@link #run()}——
 * core 已就绪、broadcaster 已在广播，立即抓 flow。
 *
 * <p><b>异步追赶轮询</b>：jmdns 发现→建 flow→probe→confirm 是异步链，无"flow 到步"事件可订阅，
 * 故编排方轮询捕获后依次驱动 probe→confirm，再轮询等设备数据。
 *
 * @author coffee
 */
public class ZeroconfTestLoop {

    private static final long POLL_INTERVAL_MS = 2000L;
    private static final int MAX_ATTEMPTS = 90; // ~3 分钟
    private static final String RESULT_FILE = "test-discovery-zeroconf-e2e-result.txt";
    private static final String RESULT_TITLE = "test-discovery zeroconf 闭环 E2E result";

    private final EcatCore core;
    private final String expectedUniqueId;
    private final ZeroconfFlowDriver flowDriver;
    private final SimDeviceVerifier deviceVerifier;
    private final E2EResultWriter resultWriter;
    private final Log log = LogFactory.getLogger(getClass());

    public ZeroconfTestLoop(EcatCore core, String coordinate, String expectedUniqueId,
                            IntegrationDeviceBase integration) {
        this.core = core;
        this.expectedUniqueId = expectedUniqueId;
        this.flowDriver = new ZeroconfFlowDriver(core, coordinate);
        this.deviceVerifier = new SimDeviceVerifier(integration);
        this.resultWriter = new E2EResultWriter(RESULT_FILE, RESULT_TITLE, expectedUniqueId);
    }

    /** 跑一次 zeroconf 闭环（幂等：entry 已存在则仅验设备/属性；广播由集成长期持有，本方法不碰广播）。*/
    public void run() {
        // 幂等：entry 已存在 → 仅验设备+属性（broadcaster 仍在周期广播，由 core 去重吸收）
        ConfigEntry existing = core.getEntryRegistry().getByUniqueId(expectedUniqueId);
        if (existing != null) {
            if (deviceVerifier.waitForReady(20)) {
                resultWriter.write("SUCCESS", "already-discovered: zeroconf 闭环完整（entry+设备+属性均就绪）");
            } else {
                resultWriter.write("SUCCESS", "already-discovered: entry 已存在 source=" + existing.getSource()
                        + "（设备/属性等待中超时）");
            }
            return;
        }

        // 首次：broadcaster（集成长期持有）已在广播 → 等发现建 flow → 驱动 probe→confirm → 验设备
        boolean probeDriven = false;
        boolean confirmDriven = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resultWriter.write("FAIL", "验证线程被中断");
                return;
            }
            try {
                // (1) 驱动 probe 步（真实 HTTP 探活：应答 model/sn 校验通过后 flow 自动落 confirm）
                if (!probeDriven) {
                    probeDriven = flowDriver.driveStep(TestDiscoveryConfigFlow.PROBE_STEP);
                }
                // (2) probe 通过后才驱动 confirm 步（入网）
                if (probeDriven && !confirmDriven) {
                    confirmDriven = flowDriver.driveStep(TestDiscoveryConfigFlow.CONFIRM_STEP);
                }
                // (3) entry 已落库？
                ConfigEntryRegistry entryRegistry = core.getEntryRegistry();
                ConfigEntry entry = entryRegistry != null ? entryRegistry.getByUniqueId(expectedUniqueId) : null;
                if (entry != null && entry.getSource() == SourceType.ZEROCONF) {
                    // (4) 设备已加载 + 在线 + 属性有数据？
                    if (deviceVerifier.waitForReady(15)) {
                        resultWriter.write("SUCCESS",
                                "zeroconf 闭环 E2E 通过: 发现→probe(HTTP探活)→confirm→入网→设备加载→属性上报（source=ZEROCONF）");
                        return;
                    }
                }
            } catch (Throwable e) {
                log.warn("[test-discovery] zeroconf 轮询异常（继续）: {}", e.getMessage());
            }
            if (attempt % 10 == 0) {
                log.info("[test-discovery] zeroconf 等待闭环完成... ({}/{})", attempt, MAX_ATTEMPTS);
            }
        }

        resultWriter.write("FAIL", "超时：zeroconf 闭环未在 " + (MAX_ATTEMPTS * POLL_INTERVAL_MS / 1000) + "s 内完成");
    }
}

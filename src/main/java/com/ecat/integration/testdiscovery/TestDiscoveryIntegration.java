/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.testdiscovery;

import com.ecat.core.Bus.BusTopic;
import com.ecat.core.Bus.EventSubscriber;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationDeviceBase;
import com.ecat.core.Integration.IntegrationLoadOption;

import com.ecat.integration.testdiscovery.importflow.ImportFlowTestDriver;
import com.ecat.integration.testdiscovery.zeroconf.ProbeHttpServer;
import com.ecat.integration.testdiscovery.zeroconf.TestDiscoverySimulatedDevice;
import com.ecat.integration.testdiscovery.zeroconf.ZeroconfServiceBroadcaster;
import com.ecat.integration.testdiscovery.zeroconf.ZeroconfTestLoop;
import com.ecat.integration.zeroconf.ZeroconfDiscoveryIntegration;
import com.ecat.integration.zeroconf.ZeroconfSubscription;

/**
 * test-discovery：**多类自发现测试桩**——一个模块、两类 discovery 全自包含验证（不依赖外部集成）。
 * <p>两类 E2E：
 * <ul>
 *   <li><b>IMPORT_FLOW</b>（{@link ImportFlowTestDriver}）：自包含——经 SDK 触发**本集成自己的** import-flow
 *       （discovery 步解析 payload → confirm 步 → createEntry）→ 验本集成设备(source=IMPORT_FLOW)。</li>
 *   <li><b>ZEROCONF</b>（{@link ZeroconfTestLoop}）：自包含闭环——广播 mDNS（端口/TXT 取自 {@link ProbeHttpServer}）→
 *       integration-zeroconf 发现 → 本集成 flow（discovery→<b>probe 真实 HTTP 探活</b>→confirm）→
 *       入网 → 仿真设备上报数据 → verifier 驱动 probe/confirm 两步并验设备(source=ZEROCONF)。
 *       被"发现"的设备即本入口在 {@link #onLoad} 起的 {@link ProbeHttpServer}（受探方）——故 probe 步真连必达。</li>
 * </ul>
 * <p><b>常驻广播（鲁棒性压测）</b>：入口在 {@link #onLoad} 起的 {@link ZeroconfServiceBroadcaster}
 * <b>长期常驻 + 周期重新广播</b>（默认 60s），即使设备已添加也不停——刻意持续制造"已添加设备重复广播"场景，
 * 压测 core 去重（R5/R12）鲁棒性：系统须长期反复正确忽略重复广播（不产重复 entry、不堆孤儿 flow、不刷 WARN）。
 * </ul>
 * 两种 discovery 各有独立 handler 文件（importflow/ImportFlowDiscoveryHandler、zeroconf/ZeroconfDiscoveryHandler），
 * {@link TestDiscoveryConfigFlow} 只编排（注册 handler + probe/confirm 步），便于按类型单独调整。
 *
 * <p><b>事件驱动（无就绪轮询）</b>：两类测试均由 {@link BusTopic#INTEGRATIONS_ALL_LOADED} 触发——
 * 该事件发出时所有集成已加载（core service 就绪 + integration-zeroconf 就绪），故两类 E2E 可立即跑。
 *
 * <p>"抓 confirm 步 + 等设备数据"仍保留轻量轮询——jmdns 发现→建 flow→confirm 是异步链，
 * 无"flow 到步"事件可订阅，verifier 须轮询捕获后再 submitStep(confirm)。此为异步追赶，非就绪轮询。
 *
 * @author coffee
 */
public class TestDiscoveryIntegration extends IntegrationDeviceBase {

    public static final String COORDINATE = "com.ecat:integration-test-discovery";

    /** zeroconf 测试服务类型（广播/订阅用）*/
    private static final String ZEROCONF_SERVICE_TYPE = "_ecat-test._tcp.local.";
    /** zeroconf 闭环预期 uniqueId（与广播 TXT sn + flow 前缀约定）*/
    public static final String EXPECTED_ZEROCONF_UNIQUE_ID = "testdiscovery_zeroconf001";

    private ImportFlowTestDriver importFlowDriver;
    private ZeroconfTestLoop zeroconfLoop;
    /** zeroconf 闭环的"被发现的设备"侧（受探 HTTP server）。*/
    private ProbeHttpServer probeServer;
    /** zeroconf 广播方（长期常驻 + 周期重新广播，压测已添加设备重复广播的去重鲁棒性）。*/
    private ZeroconfServiceBroadcaster broadcaster;

    @Override
    public void onLoad(EcatCore core, IntegrationLoadOption loadOption) {
        super.onLoad(core, loadOption); // 注入 core/log/registries

        // 起 probe HTTP server（被发现的"设备"侧）——必须在 INTEGRATIONS_ALL_LOADED 广播前就绪，
        // 否则 probe 步真实 HTTP GET /identify 不可达。onLoad 是最早的可靠落点（早于 onStart / 事件触发）。
        probeServer = new ProbeHttpServer();
        probeServer.start();
        log.info("[test-discovery] probe HTTP server 已启动: 0.0.0.0:{} (GET {})",
                ProbeHttpServer.PORT, ProbeHttpServer.IDENTIFY_PATH);

        // 起 zeroconf 广播方（长期常驻 + 周期重新广播）——与 ProbeHttpServer 同级、集成生命周期内常驻。
        // 即使设备已添加也持续周期广播，刻意制造"已添加设备重复广播"压测 core 去重（R5/R12）鲁棒性。
        // 启动失败不阻断集成加载（test stub 鲁棒性：广播不可用仅影响 zeroconf E2E，不应拖垮 core）。
        broadcaster = new ZeroconfServiceBroadcaster();
        try {
            broadcaster.start();
        } catch (Exception e) {
            log.error("[test-discovery] zeroconf broadcaster 启动失败，周期广播/鲁棒性压测将不可用", e);
            broadcaster = null;
        }

        // zeroconf 闭环：订阅 integration-zeroconf（声明本集成关注 _ecat-test._tcp.local.）
        subscribeZeroconf();

        importFlowDriver = new ImportFlowTestDriver(core, this);
        zeroconfLoop = new ZeroconfTestLoop(core, COORDINATE, EXPECTED_ZEROCONF_UNIQUE_ID, this);

        // 事件驱动：所有集成加载完后跑两类 E2E（无就绪轮询）
        core.getBusRegistry().subscribe(
            BusTopic.INTEGRATIONS_ALL_LOADED.getTopicName(),
            new EventSubscriber() {
                @Override
                public void handleEvent(String topic, Object eventData) {
                    new Thread(importFlowDriver::runE2E, "test-discovery-importflow").start();
                    new Thread(zeroconfLoop::run, "test-discovery-zeroconf").start();
                }
            }
        );

        log.info("[test-discovery] loaded; 已订阅 INTEGRATIONS_ALL_LOADED（事件驱动跑 import-flow + zeroconf 两类 E2E）");
    }

    @Override
    public AbstractConfigFlow getConfigFlow() {
        return new TestDiscoveryConfigFlow();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (probeServer != null) {
            probeServer.stop();
        }
        if (broadcaster != null) {
            broadcaster.stop();
        }
    }

    @Override
    public void onRelease() {
        if (broadcaster != null) {
            broadcaster.stop();
            broadcaster = null;
        }
        if (probeServer != null) {
            probeServer.stop();
            probeServer = null;
        }
        super.onRelease();
    }

    /**
     * entry → 仿真设备（zeroconf 闭环的设备侧）。
     * <p>必须 load(core)+init()（Device.core NULL bug 防御；IntegrationDeviceBase.createEntry 默认只 addDevice+start 不 init）。
     */
    @Override
    protected DeviceBase createDeviceFromEntry(ConfigEntry entry) {
        TestDiscoverySimulatedDevice device = new TestDiscoverySimulatedDevice(entry);
        device.load(core);
        device.init();
        log.info("[test-discovery] 仿真设备已加载: uniqueId={}", entry.getUniqueId());
        return device;
    }

    /** 向 integration-zeroconf 声明订阅（若未加载则告警，zeroconf 闭环测试将失败）。*/
    private void subscribeZeroconf() {
        Object z = core.getIntegrationRegistry().getIntegration(ZeroconfDiscoveryIntegration.COORDINATE);
        if (z instanceof ZeroconfDiscoveryIntegration) {
            ((ZeroconfDiscoveryIntegration) z).subscribe(COORDINATE,
                    new ZeroconfSubscription.Builder().type(ZEROCONF_SERVICE_TYPE).build());
        } else {
            log.warn("[test-discovery] integration-zeroconf 未加载，zeroconf 闭环测试将失败");
        }
    }
}

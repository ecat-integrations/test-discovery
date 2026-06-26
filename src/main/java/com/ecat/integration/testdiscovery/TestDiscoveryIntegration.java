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
import com.ecat.integration.testdiscovery.zeroconf.ZeroconfDemoDriver;
import com.ecat.integration.testdiscovery.zeroconf.ZeroconfServiceBroadcaster;
import com.ecat.integration.zeroconf.ZeroconfDiscoveryIntegration;
import com.ecat.integration.zeroconf.ZeroconfSubscription;

/**
 * test-discovery：**自发现测试桩**——一个模块、两类 discovery（IMPORT_FLOW 自动 E2E + ZEROCONF 演示 pending）。
 *
 * <p><b>发现 ≠ 添加</b>（见 require/ecat-core-api/15-discovery-add-separation.md）：被动发现的 zeroconf 设备
 * 只进 pending 列表等用户决策，不自动入网；import_flow 是程序内主动调用，由调用方显式推进 flow 跑完。
 *
 * <p>两类 discovery：
 * <ul>
 *   <li><b>IMPORT_FLOW 自动 E2E</b>（{@link ImportFlowTestDriver}）：自包含——经 SDK 触发本集成 import-flow
 *       （discovery 步解析 payload → confirm 步 → createEntry）→ 验本集成设备(source=IMPORT_FLOW)。
 *       调用方显式 <code>startDiscoveryFlow + submitStep</code>（程序内两步分离，无便捷封装）。</li>
 *   <li><b>ZEROCONF 演示 pending</b>（{@link ZeroconfDemoDriver}）：广播 mDNS（端口/TXT 取自 {@link ProbeHttpServer}）→
 *       integration-zeroconf 发现 → core 自动 <code>startDiscoveryFlow(ZEROCONF)</code> → flow 落 probe 步 SHOW_FORM →
 *       <b>进 pending 等用户</b>。demo driver 仅轮询确认 pending 建立并日志提示，<b>不 submitStep、不验设备</b>——
 *       真人通过 web /discoveries 点【添加】触发 flow（probe 真连 ProbeHttpServer 验证通讯 → confirm → createEntry）
 *       或【忽略】。</li>
 * </ul>
 * <p><b>常驻广播</b>：{@link ZeroconfServiceBroadcaster} 长期常驻 + 周期重新广播（默认 60s），制造重复 discovery 场景——
 * 验证 core 的 <b>R12 续期</b>（同 payload 重复 discovery 续期现有 flow，非新建）+ <b>IGNORE 抑制</b>（已忽略设备不再进 pending）。
 * </ul>
 * 两种 discovery 各有独立 handler 文件（importflow/ImportFlowDiscoveryHandler、zeroconf/ZeroconfDiscoveryHandler），
 * {@link TestDiscoveryConfigFlow} 只编排（注册 handler + probe/confirm 步），便于按类型单独调整。
 *
 * <p><b>事件驱动</b>：两类均由 {@link BusTopic#INTEGRATIONS_ALL_LOADED} 触发（此时 core + integration-zeroconf 均就绪）。
 * demo driver 的 pending 确认轮询是异步追赶（jmdns 发现链异步），非就绪轮询。
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
    private ZeroconfDemoDriver zeroconfDemo;
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
        zeroconfDemo = new ZeroconfDemoDriver(core, EXPECTED_ZEROCONF_UNIQUE_ID);

        // 事件驱动：所有集成加载完后跑 import-flow E2E（自动入网）+ zeroconf demo（进 pending 等用户）
        core.getBusRegistry().subscribe(
            BusTopic.INTEGRATIONS_ALL_LOADED.getTopicName(),
            new EventSubscriber() {
                @Override
                public void handleEvent(String topic, Object eventData) {
                    new Thread(importFlowDriver::runE2E, "test-discovery-importflow").start();
                    new Thread(zeroconfDemo::run, "test-discovery-zeroconf").start();
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

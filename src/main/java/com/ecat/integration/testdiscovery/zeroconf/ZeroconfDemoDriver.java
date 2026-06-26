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

import com.ecat.core.ConfigEntry.ConfigEntryRegistry;
import com.ecat.core.ConfigFlow.ConfigFlowService;
import com.ecat.core.ConfigFlow.DiscoveryFlowInfo;
import com.ecat.core.EcatCore;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * zeroconf 发现 demo driver——演示"发现 → 进 pending 等用户"(发现 ≠ 添加)。
 *
 * <p><b>职责</b>:仅轻轮询确认 zeroconf discovery flow 已被 integration-zeroconf 触发并停进 pending 列表,
 * 然后日志提示真人通过 web 处理。<b>不 submitStep、不验设备、不写结果文件</b>——
 * flow 内容(discovery→probe→confirm)完整保留,等真人点【添加】触发:
 * <ul>
 *   <li>【添加】→ flow probe 步真连 {@link ProbeHttpServer} 验证通讯并取信息 → confirm → CREATE_ENTRY → 设备入网;</li>
 *   <li>【忽略】→ 建 IGNORE entry 抑制后续重复发现。</li>
 * </ul>
 *
 * <p><b>设计意图</b>:zeroconf 是被动发现,自动化闭环(自动 submitStep 入网)会违背"等用户"。
 * 故本 driver 只造 pending、留决策给真人;自动化入网 E2E 由 {@code ImportFlowTestDriver}(程序内调用)承担。
 *
 * <p><b>"轮询"性质</b>:jmdns 发现→建 flow 是异步链,无"flow 到 pending"事件可订阅,
 * 故 driver 轮询 {@link ConfigFlowService#listDiscoveryFlows()} 捕获确认后即停。此为异步追赶,非就绪轮询。
 *
 * <p>本 driver 承接原已删除的若干废弃 driver 的 pending 演示职责（发现进 pending、不模拟点击入网）。
 *
 * @author coffee
 */
public class ZeroconfDemoDriver {

    private static final int CONFIRM_POLL_MAX_SEC = 30;

    private final EcatCore core;
    private final String expectedUniqueId;
    private final Log log = LogFactory.getLogger(getClass());

    public ZeroconfDemoDriver(EcatCore core, String expectedUniqueId) {
        this.core = core;
        this.expectedUniqueId = expectedUniqueId;
    }

    /**
     * 演示:轻轮询确认 zeroconf 发现已进 pending,日志提示真人处理;不推进 flow。
     * 幂等:entry 已存在(已配置/已忽略)则日志说明,不算失败。
     */
    public void run() {
        ConfigFlowService service = core.getConfigFlowService();
        if (service == null) {
            log.warn("[test-discovery] ConfigFlowService 不可用,zeroconf demo 跳过");
            return;
        }

        // 异步追赶:jmdns 发现链(integration-zeroconf serviceResolved → startDiscoveryFlow)需时间建立
        for (int sec = 0; sec < CONFIRM_POLL_MAX_SEC; sec++) {
            boolean inPending = false;
            for (DiscoveryFlowInfo d : service.listDiscoveryFlows()) {
                if ("ZEROCONF".equals(d.getSource()) && expectedUniqueId.equals(d.getUniqueId())) {
                    inPending = true;
                    break;
                }
            }
            if (inPending) {
                log.info("[test-discovery] zeroconf 发现已进 pending(等用户处理): uniqueId={}", expectedUniqueId);
                log.info("[test-discovery] demo 就绪——请通过 web /discoveries 点【添加】(flow probe 验证通讯→confirm→入网)或【忽略】");
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[test-discovery] zeroconf demo 轮询被中断");
                return;
            }
        }

        // 未观察到 pending:通常是 entry 已存在(已配置→去重抑制 / 已忽略→IGNORE 抑制),幂等不算失败
        ConfigEntryRegistry reg = core.getEntryRegistry();
        if (reg != null && reg.getByUniqueId(expectedUniqueId) != null) {
            log.info("[test-discovery] zeroconf demo entry 已存在(已配置/已忽略,发现被抑制),无需演示 pending: uniqueId={}",
                    expectedUniqueId);
            return;
        }
        log.warn("[test-discovery] zeroconf demo 未在 {}s 内观察到 pending flow(integration-zeroconf 可能未加载或广播未就绪)",
                CONFIRM_POLL_MAX_SEC);
    }
}

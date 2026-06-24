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

import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.FlowContext;

import com.ecat.integration.testdiscovery.importflow.ImportFlowDiscoveryHandler;
import com.ecat.integration.testdiscovery.zeroconf.ProbeHttpClient;
import com.ecat.integration.testdiscovery.zeroconf.ZeroconfDiscoveryHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * test-discovery 的 ConfigFlow（**多类发现编排**）。
 * <p>每种 discovery 类型一个独立 handler 文件，本类只做编排（注册 handler + 注册步骤），便于后续按发现类型单独调整：
 * <ul>
 *   <li>{@link ImportFlowDiscoveryHandler} —— IMPORT_FLOW：解析 payload.data(model|sn) → 预填 → 直达 confirm</li>
 *   <li>{@link ZeroconfDiscoveryHandler} —— ZEROCONF：解析 TXT(model/sn) → 预填 ip/port/model/sn → 落 probe</li>
 * </ul>
 *
 * <p><b>步骤结构</b>：
 * <ul>
 *   <li><b>IMPORT_FLOW</b>：discovery → <b>confirm</b>（payload 可信，无需探活）</li>
 *   <li><b>ZEROCONF</b>：discovery → <b>probe</b>（真实 HTTP 探活校验 model/sn）→ <b>confirm</b></li>
 * </ul>
 * 两类 discovery 都到达共享 <b>confirm</b> 步，由 flow 内 {@link #createEntry()} 收尾。
 *
 * <p>handler 用方法引用注册（非 implements，避免 DiscoveryHandler 擦除冲突）。
 *
 * @author coffee
 */
public class TestDiscoveryConfigFlow extends AbstractConfigFlow {

    /** probe 步 id（ZEROCONF 专用：真实 HTTP 探活）。*/
    public static final String PROBE_STEP = "probe";
    /** 共享 confirm 步 id（确认后入库）。*/
    public static final String CONFIRM_STEP = "confirm";

    public TestDiscoveryConfigFlow() {
        ImportFlowDiscoveryHandler importFlowHandler = new ImportFlowDiscoveryHandler();
        ZeroconfDiscoveryHandler zeroconfHandler = new ZeroconfDiscoveryHandler();

        // 每种 discovery 一个 handler（独立文件），方法引用注册
        registerStepDiscovery(SourceType.IMPORT_FLOW, importFlowHandler::discovery);
        registerStepDiscovery(SourceType.ZEROCONF, zeroconfHandler::discovery);

        // zeroconf 专用 probe 步：真实 HTTP 探活（discovery → probe → confirm）
        registerStep(PROBE_STEP, this::probe, "探活验证");
        // 共享 confirm 步：确认后入库（IMPORT_FLOW 直达；ZEROCONF 经 probe 到达）
        registerStep(CONFIRM_STEP, this::confirmEntry, "确认入网");
    }

    /**
     * probe step（ZEROCONF 专用）：用预填的 ip/port 真连 {@code /identify}，校验应答 model/sn。
     * <p>通过 → 落 confirm；不可达/不符 → ABORT（不入网离线/伪装设备）。严格模式：ip/port 缺失明确 ABORT。
     * <p>{@code registerStep} 回调签名无 {@link FlowContext} 参数，经 {@link #getContext()}（AbstractConfigFlow 公有）
     * 读 discovery 步预填的连接信息。
     */
    private ConfigFlowResult probe(Map<String, Object> userInput) {
        FlowContext ctx = getContext();
        Object ipObj = ctx.getEntryData("ip");
        Object portObj = ctx.getEntryData("port");
        if (!(ipObj instanceof String) || !(portObj instanceof Number)) {
            return ConfigFlowResult.abort("探活前置数据缺失：ip/port 未预填 (ip=" + ipObj + ", port=" + portObj + ")");
        }
        String ip = (String) ipObj;
        int port = ((Number) portObj).intValue();
        String expectedModel = String.valueOf(ctx.getEntryData("model"));
        String expectedSn = String.valueOf(ctx.getEntryData("sn"));

        boolean ok = ProbeHttpClient.probe(ip, port, expectedModel, expectedSn);
        if (!ok) {
            return ConfigFlowResult.abort("探活失败：不可达或 model/sn 不符 (ip=" + ip + ":" + port
                    + ", expect model=" + expectedModel + "/sn=" + expectedSn + ")");
        }
        return ConfigFlowResult.showForm(CONFIRM_STEP, ZeroconfDiscoveryHandler.buildConfirmSchema(ctx),
                new HashMap<>(), ctx);
    }

    /** confirm step：确认后入库。两类 discovery 共用。*/
    private ConfigFlowResult confirmEntry(Map<String, Object> userInput) {
        return createEntry();
    }
}

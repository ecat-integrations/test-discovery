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

import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.TextConfigItem;
import com.ecat.core.ConfigFlow.FlowContext;

import com.ecat.integration.zeroconf.ZeroconfDiscoveryPayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ZEROCONF discovery 的 flow handler（test-discovery 自包含：被 mDNS 发现触发时创建本集成设备）。
 * <p>独立文件——与 {@link com.ecat.integration.testdiscovery.importflow.ImportFlowDiscoveryHandler} 分离。
 *
 * <p><b>三步结构（discovery → probe → confirm）</b>：本 handler 只负责 discovery 步——解析 TXT → 校验 →
 * 预填 entryData（含连接用 ip/port）→ 落 <b>probe</b> 步。真实探活（HTTP GET /identify）在
 * {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#probe} 步执行（probe 通过才落 confirm）。
 * IMPORT_FLOW 不经 probe（payload 可信），直接落 confirm。
 *
 * <p>uniqueId 派生：{@code testdiscovery_<sn>}（统一前缀，与广播方约定）。
 *
 * @author coffee
 */
public class ZeroconfDiscoveryHandler {

    /** uniqueId 前缀（与广播方约定）。*/
    public static final String UNIQUE_ID_PREFIX = "testdiscovery_";

    /** probe 步 id（与 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#PROBE_STEP} 约定一致）。*/
    private static final String PROBE_STEP = "probe";

    /**
     * ZEROCONF discovery step：解析 TXT → 校验 → 预填 ip/port/model/sn → 落 probe 步。
     * <p>严格模式：TXT 缺 model/sn 即 ABORT（不兜底）。
     */
    public ConfigFlowResult discovery(ZeroconfDiscoveryPayload payload, FlowContext ctx) {
        Map<String, String> txt = payload.getProperties();
        String model = txt.get("model");
        String sn = txt.get("sn");
        if (model == null || model.isEmpty() || sn == null || sn.isEmpty()) {
            return ConfigFlowResult.abort("TXT 缺 model/sn，非本测试设备: " + payload);
        }

        // 挑 IPv4（地址可能含 IPv6，含 ':' 跳过）；挑不到则 ip=null，probe 步会明确 ABORT
        String ip = pickIpv4(payload.getAddresses());
        int port = payload.getPort();
        ctx.setEntryData("model", model);
        ctx.setEntryData("sn", sn);
        ctx.setEntryData("name", payload.getName());
        ctx.setEntryData("ip", ip);
        ctx.setEntryData("port", port);
        ctx.setEntryData("vendor", txt.getOrDefault("vendor", "ECAT-Test"));
        ctx.setEntryTitle(payload.getName());
        ctx.setEntryUniqueId(UNIQUE_ID_PREFIX + sn, false);

        // 落 probe 步：展示待探活的设备 + 连接信息（下一步真连 HTTP /identify 校验 model/sn）
        ConfigSchema schema = new ConfigSchema()
                .addField(new TextConfigItem("probe_info", false,
                        "已发现设备，待探活验证：\n  model=" + model + "\n  sn=" + sn
                                + "\n  ip=" + ip + ":" + port
                                + "\n\n下一步将 HTTP GET http://" + ip + ":" + port
                                + ProbeHttpServer.IDENTIFY_PATH + " 探活（校验 model/sn）。")
                        .displayName("探活验证"));
        return ConfigFlowResult.showForm(PROBE_STEP, schema, new HashMap<>(), ctx);
    }

    /**
     * probe 通过后，confirm 步展示的设备信息 schema（读 ctx 预填值）。
     * <p>由 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#probe} 在探活通过时调用。
     */
    public static ConfigSchema buildConfirmSchema(FlowContext ctx) {
        Object model = ctx.getEntryData("model");
        Object sn = ctx.getEntryData("sn");
        Object ip = ctx.getEntryData("ip");
        Object port = ctx.getEntryData("port");
        return new ConfigSchema()
                .addField(new TextConfigItem("confirm_info", false,
                        "探活通过，确认入网：\n  model=" + model + "\n  sn=" + sn
                                + "\n  ip=" + ip + ":" + port
                                + "\n\n确认后加载仿真设备并上报温度/湿度/rssi。")
                        .displayName("确认入网"));
    }

    /** 挑首个 IPv4（含 ':' 视为 IPv6 跳过；空则返回 null）。*/
    private static String pickIpv4(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        for (String a : addresses) {
            if (a != null && !a.contains(":")) {
                return a;
            }
        }
        return null;
    }
}

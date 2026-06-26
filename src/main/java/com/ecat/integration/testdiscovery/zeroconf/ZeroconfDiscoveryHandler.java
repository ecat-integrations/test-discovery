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
 * <p><b>步骤结构（discovery → probe → credentials → confirm，探活失败另走 probe_failed 支路）</b>：
 * 本 handler 只负责 discovery 步——解析 TXT → 校验 → 预填 entryData（含连接用 ip/port/model/sn）→ 落
 * <b>probe</b> 步。后续步骤的 schema 构建器（{@link #buildProbeSuccessSchema}/{@link #buildCredentialsSchema}/
 * {@link #buildConfirmSchema}/{@link #buildProbeFailedSchema}）集中在本类，由
 * {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow} 编排调用：probe 真连 HTTP GET /identify
 * 探活 → 通过则用户在凭证步输入账号密码 → 确认步汇总 → createEntry；探活失败则进 probe_failed 步，
 * 用户点【下一步】结束流程。入网后设备用凭证访问 {@link ProbeHttpServer#DATA_PATH} 拉数据（真实认证入网示例）。
 * IMPORT_FLOW 不经 probe/凭证步（payload 可信，凭证在 payload 内），直达 confirm。
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
     * probe 探活通过步的 schema：只读展示已验证的设备信息（探活在线 + model/sn 匹配）。
     * <p>由 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#probe} 在探活通过、
     * 且为 status 重放（getStatus）时调用——用户查看确认后点【下一步】（submit）推进到凭证步。
     */
    public static ConfigSchema buildProbeSuccessSchema(FlowContext ctx) {
        Object model = ctx.getEntryData("model");
        Object sn = ctx.getEntryData("sn");
        Object ip = ctx.getEntryData("ip");
        Object port = ctx.getEntryData("port");
        return new ConfigSchema()
                .addField(new TextConfigItem("probe_info", false,
                        "探活通过 · " + model + " · sn=" + sn + " · " + ip + ":" + port)
                        .displayName("设备信息（探活通过）").readOnly(true)
                        .description("设备在线且 model/sn 与广播一致。请查看确认，点击【下一步】设置访问凭证。"));
    }

    /**
     * 凭证步 schema：只读提示测试设备默认凭证（admin/123）+ account/password 输入。
     * <p>由 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#credentials} 调用。
     * 默认凭证写明在只读提示字段，避免用户不知测试设备该输什么。
     */
    public static ConfigSchema buildCredentialsSchema(FlowContext ctx) {
        return new ConfigSchema()
                .addField(new TextConfigItem("creds_hint", false,
                        "测试设备默认凭证：账号 " + ProbeHttpServer.AUTH_ACCOUNT
                                + " / 密码 " + ProbeHttpServer.AUTH_PASSWORD)
                        .displayName("凭证提示").readOnly(true))
                .addField(new TextConfigItem("account", true).displayName("账号"))
                .addField(new TextConfigItem("password", true).displayName("密码"));
    }

    /**
     * reconfigure 凭证步 schema：account 预填当前账号（可改），password 空（重新输入）。
     * <p>由 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#reconfigureCredentials} 调用。
     * reconfigure <b>只改访问凭证</b>（account/password），不改设备类型/连接地址（ip/port/model/sn 保留原值）。
     * account 用 defaultValue 预填当前值（前端 text-field 渲染 {@code value ?? defaultValue}），方便用户在原值上修改；
     * password 不预填（安全：需重新输入确认）。
     */
    public static ConfigSchema buildReconfigureSchema(FlowContext ctx) {
        Object currentAccount = ctx.getEntryData("account");
        String accountDefault = currentAccount != null ? String.valueOf(currentAccount) : "";
        String accountDisplay = currentAccount != null ? String.valueOf(currentAccount) : "（无）";
        return new ConfigSchema()
                .addField(new TextConfigItem("reconfigure_hint", false,
                        "修改访问设备的凭证（仅改账号/密码，不改设备类型与连接地址）。当前账号：" + accountDisplay)
                        .displayName("凭证提示").readOnly(true))
                .addField(new TextConfigItem("account", true, accountDefault).displayName("账号"))
                .addField(new TextConfigItem("password", true).displayName("密码")
                        .description("输入新的访问密码，提交时会当场验证；通过后设备用新凭证拉数据。"));
    }

    /**
     * 确认步 schema：汇总设备信息 + 账号（**不回显密码**），只读展示。
     * <p>由 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#confirmEntry} 在 status 重放时调用。
     */
    public static ConfigSchema buildConfirmSchema(FlowContext ctx) {
        Object model = ctx.getEntryData("model");
        Object sn = ctx.getEntryData("sn");
        Object ip = ctx.getEntryData("ip");
        Object port = ctx.getEntryData("port");
        Object account = ctx.getEntryData("account");
        String addr = (ip != null && port != null) ? " · " + ip + ":" + port : "";
        return new ConfigSchema()
                .addField(new TextConfigItem("summary", false,
                        model + " · sn=" + sn + addr + " · 账号 " + account)
                        .displayName("确认入网信息").readOnly(true)
                        .description("请确认以上信息，点击【下一步】保存并完成入网（密码不入汇总）。"));
    }

    /**
     * 探活失败步 schema：提示设备无法访问，用户点【下一步】结束本次添加。
     * <p>由 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#probeFailed} 调用。
     */
    public static ConfigSchema buildProbeFailedSchema() {
        return new ConfigSchema()
                .addField(new TextConfigItem("probe_failed", false, "设备无法访问")
                        .displayName("探活失败").readOnly(true)
                        .description("设备不可达或 model/sn 不符。请重启设备后重新发现，点击【下一步】结束本次添加。"));
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

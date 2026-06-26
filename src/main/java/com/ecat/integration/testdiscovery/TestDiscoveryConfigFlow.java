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
import com.ecat.core.ConfigFlow.ConfigSchema;
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
 *   <li><b>IMPORT_FLOW</b>：discovery → <b>confirm</b>（payload 可信带凭证，无需探活/凭证步）</li>
 *   <li><b>ZEROCONF</b>：discovery → <b>probe</b>（真实 HTTP 探活 + 设备信息展示）→
 *       <b>credentials</b>（用户输入账号密码，<b>当场真连 /data 验证</b>——错误凭证不放行入网）→
 *       <b>confirm</b>（汇总确认）；探活失败 → <b>probe_failed</b>（用户点【下一步】结束流程）</li>
 *   <li><b>reconfigure</b>（已入网设备改凭证）：用户改 account/password，<b>当场验证新凭证</b>后入库；
 *       框架按 RECONFIGURE 重建设备读新凭证——避免"错凭证入网→设备 OFFLINE→无法 reconfigure"的两头堵</li>
 * </ul>
 * 两类 discovery 都到达共享 <b>confirm</b> 步，由 flow 内 {@link #createEntry()} 收尾。
 * 入网后仿真设备用 entry 内的账号密码访问 {@link com.ecat.integration.testdiscovery.zeroconf.ProbeHttpServer#DATA_PATH}
 * 拉取传感器数据（真实认证入网示例）。
 *
 * <p>handler 用方法引用注册（非 implements，避免 DiscoveryHandler 擦除冲突）。
 *
 * @author coffee
 */
public class TestDiscoveryConfigFlow extends AbstractConfigFlow {

    /** probe 步 id（ZEROCONF 专用：真实 HTTP 探活 + 设备信息展示）。*/
    public static final String PROBE_STEP = "probe";
    /** probe_failed 步 id（探活失败支路：用户点【下一步】结束流程）。*/
    public static final String PROBE_FAILED_STEP = "probe_failed";
    /** 凭证步 id（ZEROCONF：输入访问设备的账号密码）。*/
    public static final String CREDENTIALS_STEP = "credentials";
    /** 共享 confirm 步 id（汇总确认后入库）。*/
    public static final String CONFIRM_STEP = "confirm";
    /** reconfigure 步 id（已入网设备只改访问凭证 account/password，不改设备类型/连接地址）。*/
    public static final String RECONFIGURE_STEP = "reconfigure";

    public TestDiscoveryConfigFlow() {
        ImportFlowDiscoveryHandler importFlowHandler = new ImportFlowDiscoveryHandler();
        ZeroconfDiscoveryHandler zeroconfHandler = new ZeroconfDiscoveryHandler();

        // 每种 discovery 一个 handler（独立文件），方法引用注册
        registerStepDiscovery(SourceType.IMPORT_FLOW, importFlowHandler::discovery);
        registerStepDiscovery(SourceType.ZEROCONF, zeroconfHandler::discovery);

        // ZEROCONF 专用 probe 步：真实 HTTP 探活（discovery → probe）
        registerStep(PROBE_STEP, this::probe, "探活验证");
        // 探活失败支路（probe → probe_failed）：用户确认后结束
        registerStep(PROBE_FAILED_STEP, this::probeFailed, "探活失败");
        // ZEROCONF 凭证步（probe → credentials）：用户输入账号密码，提交时当场验证（见 credentials）
        registerStep(CREDENTIALS_STEP, this::credentials, "访问凭证");
        // 共享 confirm 步：汇总确认后入库（IMPORT_FLOW 直达；ZEROCONF 经 probe→credentials 到达）
        registerStep(CONFIRM_STEP, this::confirmEntry, "确认入网");

        // reconfigure：已入网设备改访问凭证（只改 account/password）。用户输错凭证入网导致设备无法通讯时，
        // 可经此步改对——避免"错凭证入网→OFFLINE→reconfigure 无可用流程"的两头堵。
        registerStepReconfigure(RECONFIGURE_STEP, "重新配置访问凭证", this::reconfigureCredentials);
    }

    /**
     * probe step（ZEROCONF 专用）：始终先真连 {@code /identify} 校验 model/sn。
     * <p>探活通过：status 重放(getStatus, userInput==null)→展示已验证设备信息供查看；
     * 用户提交(submit, userInput!=null)→推进到凭证步。<b>始终探活</b>——不在过期成功上推进（严格模式）。
     * <p>探活失败（不可达/model/sn 不符）→ 落 probe_failed 步（用户点【下一步】结束，非硬 ABORT）。
     * ip/port 未预填属 discovery 解析异常 → 直接 ABORT 给明确错误（非设备不可达，不混入 probe_failed）。
     * <p>{@code registerStep} 回调无 {@link FlowContext} 参数，经 {@link #getContext()} 读 discovery 步预填值。
     */
    private ConfigFlowResult probe(Map<String, Object> userInput) {
        FlowContext ctx = getContext();
        Object ipObj = ctx.getEntryData("ip");
        Object portObj = ctx.getEntryData("port");
        if (!(ipObj instanceof String) || !(portObj instanceof Number)) {
            return ConfigFlowResult.abort("探活前置数据缺失：ip/port 未预填 (ip=" + ipObj + ", port=" + portObj + ")——discovery 解析异常");
        }
        String ip = (String) ipObj;
        int port = ((Number) portObj).intValue();
        String expectedModel = String.valueOf(ctx.getEntryData("model"));
        String expectedSn = String.valueOf(ctx.getEntryData("sn"));

        boolean ok = ProbeHttpClient.probe(ip, port, expectedModel, expectedSn);
        if (!ok) {
            return ConfigFlowResult.showForm(PROBE_FAILED_STEP,
                    ZeroconfDiscoveryHandler.buildProbeFailedSchema(), new HashMap<>(), ctx);
        }
        if (userInput != null) {
            // 用户在已验证的 probe 步点【下一步】→ 推进到凭证步
            return ConfigFlowResult.showForm(CREDENTIALS_STEP,
                    ZeroconfDiscoveryHandler.buildCredentialsSchema(ctx), new HashMap<>(), ctx);
        }
        // status 重放：展示已验证的设备信息供查看确认
        return ConfigFlowResult.showForm(PROBE_STEP,
                ZeroconfDiscoveryHandler.buildProbeSuccessSchema(ctx), new HashMap<>(), ctx);
    }

    /**
     * probe_failed step：探活失败支路。
     * <p>status 重放(userInput==null)→重新展示"无法访问"提示；用户点【下一步】(submit)→ABORT 结束本次添加。
     * 重开向导走 status 分支只展示，**不会误 ABORT**（关键：getStatus 传 null、submitStep 传 map）。
     */
    private ConfigFlowResult probeFailed(Map<String, Object> userInput) {
        if (userInput == null) {
            return ConfigFlowResult.showForm(PROBE_FAILED_STEP,
                    ZeroconfDiscoveryHandler.buildProbeFailedSchema(), new HashMap<>(), getContext());
        }
        return ConfigFlowResult.abort("设备无法访问，本次添加流程已结束。请重启设备后重新发现并添加。");
    }

    /**
     * credentials step（ZEROCONF）：输入访问设备的账号密码，提交时<b>当场验证</b>。
     * <p>status 重放→展示凭证表单；submit→校验 account/password 非空（空则带 errors 重放）→
     * <b>真连 /data（BasicAuth）验证凭证</b>（无效/不可达→带 errors 重放，<b>不放行错误凭证入网</b>，
     * 否则设备入网后无法通讯、无数据）→ 存入 entryData → 推进到 confirm 步。
     * 账号密码入网后由仿真设备用于访问 {@code /data} 拉数据。
     */
    private ConfigFlowResult credentials(Map<String, Object> userInput) {
        FlowContext ctx = getContext();
        ConfigSchema schema = ZeroconfDiscoveryHandler.buildCredentialsSchema(ctx);
        if (userInput == null) {
            return ConfigFlowResult.showForm(CREDENTIALS_STEP, schema, new HashMap<>(), ctx);
        }
        String account = trimOrNul(userInput.get("account"));
        String password = trimOrNul(userInput.get("password"));
        Map<String, Object> errors = new HashMap<>();
        if (account == null) {
            errors.put("account", "账号不能为空");
        }
        if (password == null) {
            errors.put("password", "密码不能为空");
        }
        if (!errors.isEmpty()) {
            return ConfigFlowResult.showForm(CREDENTIALS_STEP, schema, errors, ctx);
        }
        // 当场验证凭证（与 reconfigure 共用）：无效/不可达则不放行
        ConfigFlowResult verifyFail = verifyCredentialsOrFail(ctx, account, password, CREDENTIALS_STEP, schema, errors);
        if (verifyFail != null) {
            return verifyFail;
        }
        ctx.setEntryData("account", account);
        ctx.setEntryData("password", password);
        return ConfigFlowResult.showForm(CONFIRM_STEP,
                ZeroconfDiscoveryHandler.buildConfirmSchema(ctx), new HashMap<>(), ctx);
    }

    /**
     * reconfigure step（已入网设备改凭证，<b>只改 account/password</b>）。
     * <p>status 重放→凭证表单（account 预填当前值可改，password 空重新输入）；submit→非空校验→
     * <b>当场验证新凭证</b>（避免改成无效凭证后设备无法通讯）→ setEntryData 新凭证 → {@link #createEntry()}。
     * <p>sourceType=RECONFIGURE 时 {@link #createEntry()} 自动转 updateEntry（保留原 entryId、保留 ip/port/
     * model/sn 等原值，只覆盖 account/password）；框架 {@code reconfigureEntry} 重建设备 → 新设备 start()
     * 读新凭证拉数据（OFFLINE→NORMAL 自愈）。
     */
    private ConfigFlowResult reconfigureCredentials(Map<String, Object> userInput, FlowContext ctx) {
        ConfigSchema schema = ZeroconfDiscoveryHandler.buildReconfigureSchema(ctx);
        if (userInput == null) {
            return ConfigFlowResult.showForm(RECONFIGURE_STEP, schema, new HashMap<>(), ctx);
        }
        String account = trimOrNul(userInput.get("account"));
        String password = trimOrNul(userInput.get("password"));
        Map<String, Object> errors = new HashMap<>();
        if (account == null) {
            errors.put("account", "账号不能为空");
        }
        if (password == null) {
            errors.put("password", "密码不能为空");
        }
        if (!errors.isEmpty()) {
            return ConfigFlowResult.showForm(RECONFIGURE_STEP, schema, errors, ctx);
        }
        // 当场验证新凭证（与入网凭证步一致）：无效/不可达则不更新
        ConfigFlowResult verifyFail = verifyCredentialsOrFail(ctx, account, password, RECONFIGURE_STEP, schema, errors);
        if (verifyFail != null) {
            return verifyFail;
        }
        // 只覆盖凭证（ip/port/model/sn 等保留 ctx 原值不动），createEntry 按 RECONFIGURE 转 updateEntry
        ctx.setEntryData("account", account);
        ctx.setEntryData("password", password);
        return createEntry();
    }

    /**
     * 凭证当场验证（credentials 步与 reconfigure 步共用）：真连 {@code /data}（BasicAuth）。
     * <p>ip/port 由 probe 步（ZEROCONF）或 import handler 预填；此处类型不符属不变量违反 → ABORT 明确报错
     * （非设备不可达、非凭证错误，不混入重放）。凭证无效(401)/不可达 → 写入 account/password 两条 errors
     * 并返回 showForm 失败结果（调用方据此重放）；验证通过 → 返回 {@code null}（调用方存凭证 + 推进/入库）。
     *
     * @param errors 复用调用方已构建的 errors map（凭证无效时追加 account/password 两条）
     * @return 失败结果（重放/ABORT）；{@code null}=验证通过
     */
    private ConfigFlowResult verifyCredentialsOrFail(FlowContext ctx, String account, String password,
                                                     String failStepId, ConfigSchema failSchema,
                                                     Map<String, Object> errors) {
        Object ipObj = ctx.getEntryData("ip");
        Object portObj = ctx.getEntryData("port");
        if (!(ipObj instanceof String) || !(portObj instanceof Number)) {
            return ConfigFlowResult.abort("凭证验证前置数据缺失：ip/port 未预填 (ip=" + ipObj + ", port=" + portObj
                    + ")——应由 probe 步/import handler 保证");
        }
        ProbeHttpClient.SensorData verified = ProbeHttpClient.pullData(
                (String) ipObj, ((Number) portObj).intValue(), account, password);
        if (verified == null) {
            errors.put("account", "凭证验证失败：账号/密码错误，或设备不可达");
            errors.put("password", "凭证验证失败：账号/密码错误，或设备不可达");
            return ConfigFlowResult.showForm(failStepId, failSchema, errors, ctx);
        }
        return null;
    }

    /**
     * confirm step：汇总确认后入库。两类 discovery 共用。
     * <p>status 重放(userInput==null)→重新展示汇总（**不**调 createEntry，避免 getStatus 副作用——
     * 旧实现无条件 createEntry 会在 getStatus 时建 entry）；submit→createEntry 入库。
     */
    private ConfigFlowResult confirmEntry(Map<String, Object> userInput) {
        if (userInput == null) {
            return ConfigFlowResult.showForm(CONFIRM_STEP,
                    ZeroconfDiscoveryHandler.buildConfirmSchema(getContext()), new HashMap<>(), getContext());
        }
        return createEntry();
    }

    /** trim 后为空返回 null（用于非空校验：null=不通过）。*/
    private static String trimOrNul(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}

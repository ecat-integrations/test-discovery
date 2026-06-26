/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.testdiscovery.importflow;

import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.ConfigFlow.ImportFlowPayload;
import com.ecat.integration.testdiscovery.zeroconf.ProbeHttpServer;
import com.ecat.integration.testdiscovery.zeroconf.ZeroconfDiscoveryHandler;

import java.util.HashMap;

/**
 * IMPORT_FLOW discovery 的 flow handler（test-discovery 自包含：被 import-flow 触发时创建本集成设备）。
 * <p>独立文件——与 {@link com.ecat.integration.testdiscovery.zeroconf.ZeroconfDiscoveryHandler} 分离，
 * 便于各自调整优化。两种 discovery 都落地到 {@code TestDiscoveryConfigFlow} 的共享 confirm 步（由 flow
 * 内 {@code createEntry()} 收尾，故 handler 本身不构造 entry）。
 *
 * <p>payload.data 格式（v1）：{@code model|sn|name|account|password}（5 段均必填——设备入网后用账号密码
 * 访问 {@link ProbeHttpServer#DATA_PATH} 拉数据，故 import 程序须在 payload 内携带凭证）。
 * <p>uniqueId 派生：{@code testdiscovery_importflow_<sn>}（统一前缀）。
 *
 * @author coffee
 */
public class ImportFlowDiscoveryHandler {

    /** uniqueId 前缀（与驱动方约定）。*/
    public static final String UNIQUE_ID_PREFIX = "testdiscovery_importflow_";

    private static final String CONFIRM_STEP = "confirm";

    /**
     * IMPORT_FLOW discovery step：解析 payload.data → 校验 → 预填（含凭证/地址）→ 落地共享 confirm 步。
     * <p>严格模式：version/data/字段缺失即 ABORT（不兜底）。
     */
    public ConfigFlowResult discovery(ImportFlowPayload payload, FlowContext ctx) {
        if (payload.getVersion() != 1) {
            return ConfigFlowResult.abort("不支持的 import data version: " + payload.getVersion()
                    + "（本集成仅支持 version=1）");
        }
        String data = payload.getData();
        if (data == null || data.isEmpty()) {
            return ConfigFlowResult.abort("import data 为空（v1 应为 model|sn|name|account|password）");
        }
        String[] parts = data.split("\\|", -1);
        if (parts.length < 5) {
            return ConfigFlowResult.abort("import data 格式错误（v1 应为 model|sn|name|account|password）: " + data);
        }
        String model = parts[0].trim();
        String sn = parts[1].trim();
        String name = parts[2].trim();
        String account = parts[3].trim();
        String password = parts[4].trim();
        if (model.isEmpty() || sn.isEmpty() || name.isEmpty() || account.isEmpty() || password.isEmpty()) {
            return ConfigFlowResult.abort("import data 各段不能为空（model|sn|name|account|password）: " + data);
        }

        // 预填 entryData（含访问凭证/地址——设备入网后用凭证访问 ProbeHttpServer /data 拉数据）
        ctx.setEntryData("model", model);
        ctx.setEntryData("sn", sn);
        ctx.setEntryData("name", name);
        ctx.setEntryData("vendor", "ECAT-Test");
        ctx.setEntryData("account", account);
        ctx.setEntryData("password", password);
        // import 程序已知设备=本地 ProbeHttpServer（同进程），故地址固定为本机监听口
        ctx.setEntryData("ip", "127.0.0.1");
        ctx.setEntryData("port", ProbeHttpServer.PORT);
        ctx.setEntryTitle(name);
        ctx.setEntryUniqueId(UNIQUE_ID_PREFIX + sn, false);  // skipValidation=false → 启用 R5 去重

        // 落地共享 confirm 步：用共享汇总 schema（与 zeroconf 一致；confirmEntry status 重放也用此 schema）
        return ConfigFlowResult.showForm(CONFIRM_STEP,
                ZeroconfDiscoveryHandler.buildConfirmSchema(ctx), new HashMap<>(), ctx);
    }
}

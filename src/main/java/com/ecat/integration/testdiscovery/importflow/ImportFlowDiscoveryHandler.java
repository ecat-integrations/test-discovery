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
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.TextConfigItem;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.ConfigFlow.ImportFlowPayload;

import java.util.HashMap;
import java.util.Map;

/**
 * IMPORT_FLOW discovery 的 flow handler（test-discovery 自包含：被 import-flow 触发时创建本集成设备）。
 * <p>独立文件——与 {@link com.ecat.integration.testdiscovery.zeroconf.ZeroconfDiscoveryHandler} 分离，
 * 便于各自调整优化。两种 discovery 都落地到 {@code TestDiscoveryConfigFlow} 的共享 confirm 步（由 flow
 * 内 {@code createEntry()} 收尾，故 handler 本身不构造 entry）。
 *
 * <p>payload.data 格式（v1）：{@code model|sn[|name]}。
 * <p>uniqueId 派生：{@code testdiscovery_importflow_<sn>}（统一前缀）。
 *
 * @author coffee
 */
public class ImportFlowDiscoveryHandler {

    /** uniqueId 前缀（与驱动方约定）。*/
    public static final String UNIQUE_ID_PREFIX = "testdiscovery_importflow_";

    private static final String CONFIRM_STEP = "confirm";

    /**
     * IMPORT_FLOW discovery step：解析 payload.data → 校验 → 预填 → 落地 confirm 步。
     * <p>严格模式：version/data/字段缺失即 ABORT（不兜底）。
     */
    public ConfigFlowResult discovery(ImportFlowPayload payload, FlowContext ctx) {
        if (payload.getVersion() != 1) {
            return ConfigFlowResult.abort("不支持的 import data version: " + payload.getVersion()
                    + "（本集成仅支持 version=1）");
        }
        String data = payload.getData();
        if (data == null || data.isEmpty()) {
            return ConfigFlowResult.abort("import data 为空（v1 应为 model|sn[|name]）");
        }
        String[] parts = data.split("\\|", -1);
        if (parts.length < 2) {
            return ConfigFlowResult.abort("import data 格式错误（v1 应为 model|sn[|name]）: " + data);
        }
        String model = parts[0].trim();
        String sn = parts[1].trim();
        String name = (parts.length > 2 && !parts[2].trim().isEmpty())
                ? parts[2].trim() : "test-discovery import-flow " + sn;
        if (model.isEmpty() || sn.isEmpty()) {
            return ConfigFlowResult.abort("import data model/sn 不能为空: " + data);
        }

        // 预填 entryData
        ctx.setEntryData("model", model);
        ctx.setEntryData("sn", sn);
        ctx.setEntryData("name", name);
        ctx.setEntryData("vendor", "ECAT-Test");
        ctx.setEntryTitle(name);
        ctx.setEntryUniqueId(UNIQUE_ID_PREFIX + sn, false);  // skipValidation=false → 启用 R5 去重

        // 落地共享 confirm 步
        ConfigSchema schema = new ConfigSchema()
                .addField(new TextConfigItem("confirm_info", false,
                        "import-flow 已解析：\n  model=" + model + "\n  sn=" + sn
                                + "\n\n确认入网此设备？（确认后加载仿真设备并上报温度/湿度/rssi）")
                        .displayName("确认入网"));
        return ConfigFlowResult.showForm(CONFIRM_STEP, schema, new HashMap<>(), ctx);
    }
}

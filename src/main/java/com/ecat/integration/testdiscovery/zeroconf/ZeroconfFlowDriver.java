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

import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowRegistry;
import com.ecat.core.ConfigFlow.ConfigFlowService;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.EcatCore;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * zeroconf 闭环的<b>flow 驱动器</b>——在本集成的 ZEROCONF 活跃 flow 上推进指定步骤（单次尝试）。
 *
 * <p><b>单一职责</b>：定位匹配的活跃 flow（同 coordinate + source=ZEROCONF + 当前步=targetStep）并 submitStep。
 * 不关心广播 / 设备 / 结果，也不关心重试节奏（重试由编排方 {@link ZeroconfTestLoop} 的轮询决定）。
 *
 * <p><b>判步用 {@link FlowContext#getCurrentStep()}（纯读）</b>，<b>不能</b>用 {@code ConfigFlowRegistry.getStatus}
 * ——后者会重跑步骤（见 bug-record §6.1 / memory configflow-getstatus-reexecutes-step）。
 *
 * @author coffee
 */
public class ZeroconfFlowDriver {

    private final EcatCore core;
    private final String coordinate;
    private final Log log = LogFactory.getLogger(getClass());

    public ZeroconfFlowDriver(EcatCore core, String coordinate) {
        this.core = core;
        this.coordinate = coordinate;
    }

    /**
     * 找到本集成 ZEROCONF 触发的、当前停在 {@code targetStep} 的活跃 flow，submitStep 推进（单次尝试）。
     *
     * @param targetStep {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#PROBE_STEP}
     *                   或 {@link com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow#CONFIRM_STEP}
     * @return true=已驱动；false=未找到匹配 flow（core 未就绪或 flow 尚未到达该步，调用方按节奏重试）
     */
    public boolean driveStep(String targetStep) {
        ConfigFlowService service = core.getConfigFlowService();
        ConfigFlowRegistry fr = core.getFlowRegistry();
        if (service == null || fr == null) {
            return false;
        }
        for (String flowId : fr.getActiveFlowIds()) {
            AbstractConfigFlow flow = fr.getActiveFlow(flowId);
            if (flow == null) {
                continue;
            }
            FlowContext fctx = flow.getContext();
            if (fctx == null || !coordinate.equals(fctx.getCoordinate())) {
                continue;
            }
            if (flow.getSourceType() != SourceType.ZEROCONF) {
                continue;
            }
            if (targetStep.equals(fctx.getCurrentStep())) {
                Map<String, Object> input = new HashMap<String, Object>();
                input.put("confirmed", true);
                service.submitStep(flowId, targetStep, input);
                log.info("[test-discovery] zeroconf 已驱动 {} 步: flowId={}", targetStep, flowId);
                return true;
            }
        }
        return false;
    }
}

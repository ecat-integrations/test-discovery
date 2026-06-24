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

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigEntry.ConfigEntryRegistry;
import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigFlowService;
import com.ecat.core.ConfigFlow.ConfigFlowService.ConfigFlowInstance;
import com.ecat.core.ConfigFlow.ImportFlowPayload;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationDeviceBase;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

import com.ecat.integration.testdiscovery.zeroconf.TestDiscoverySimulatedDevice;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * IMPORT_FLOW discovery 的自包含 E2E 驱动器（触发 test-discovery 自己的 import-flow）。
 * <p>经 {@code core.getConfigFlowService().startDiscoveryFlow(coordinate, IMPORT_FLOW, payload)} 触发本集成 flow：
 * discovery 步解析 payload → 落 confirm 步 → 驱动方 submitStep(confirm) → createEntry → 仿真设备加载上报。
 *
 * <p>与 {@link com.ecat.integration.testdiscovery.zeroconf.ZeroconfTestLoop} 对称——一个测 IMPORT_FLOW、
 * 一个测 ZEROCONF，都创建 {@link TestDiscoverySimulatedDevice}。
 *
 * <p>由入口在 {@link com.ecat.core.Bus.BusTopic#INTEGRATIONS_ALL_LOADED} 后调用 {@link #runE2E()}。
 *
 * @author coffee
 */
public class ImportFlowTestDriver {

    private static final String COORDINATE = "com.ecat:integration-test-discovery";
    private static final String TEST_MODEL = "TestImportFlowDevice";
    private static final String TEST_SN = "IMPORTFLOW001";
    private static final String EXPECTED_UNIQUE_ID =
            ImportFlowDiscoveryHandler.UNIQUE_ID_PREFIX + TEST_SN;  // testdiscovery_importflow_IMPORTFLOW001
    private static final String CONFIRM_STEP = "confirm";
    private static final String RESULT_FILE = "test-discovery-importflow-e2e-result.txt";

    private final EcatCore core;
    private final IntegrationDeviceBase integration;
    private final Log log = LogFactory.getLogger(getClass());

    public ImportFlowTestDriver(EcatCore core, IntegrationDeviceBase integration) {
        this.core = core;
        this.integration = integration;
    }

    /** 跑一次 import-flow 自包含 E2E（幂等：entry 已存在则验设备后 SUCCESS）。*/
    public void runE2E() {
        ConfigFlowService service = core.getConfigFlowService();
        if (service == null) {
            fail("ConfigFlowService 不可用（INTEGRATIONS_ALL_LOADED 后应已就绪）");
            return;
        }

        // 0. 幂等：entry 已存在 → 验设备后 SUCCESS（不重复触发，避免 R5/Layer2 拦截）
        if (entryExists()) {
            String tail = waitForDevice(20) ? "（设备+属性均就绪）" : "（设备/属性等待中超时）";
            String summary = "import-flow 自包含 E2E 幂等命中：entry 已存在 uniqueId=" + EXPECTED_UNIQUE_ID
                    + ", source=IMPORT_FLOW" + tail;
            log.info("[test-discovery] ✅ {}", summary);
            writeReport("SUCCESS", summary);
            return;
        }

        try {
            // 1. 触发本集成自己的 import-flow：payload.data = model|sn|name（v1）
            ImportFlowPayload payload = new ImportFlowPayload(COORDINATE, 1,
                    TEST_MODEL + "|" + TEST_SN + "|" + "test-discovery import-flow " + TEST_SN);
            ConfigFlowInstance inst = service.startDiscoveryFlow(COORDINATE, SourceType.IMPORT_FLOW, payload);
            String flowId = inst.getFlowId();
            log.info("[test-discovery] import-flow 1/3 init: flowId={}, step={}, type={}",
                    flowId, inst.getStepId(), inst.getResult().getType());
            assertShowForm(inst, CONFIRM_STEP, "import-flow discovery 应落 confirm 步");

            // 2. 驱动 confirm → createEntry
            Map<String, Object> confirm = new HashMap<>();
            confirm.put("confirmed", true);
            inst = service.submitStep(flowId, CONFIRM_STEP, confirm);
            log.info("[test-discovery] import-flow 2/3 confirm: type={}, savedEntryId={}",
                    inst.getResult().getType(), inst.getSavedEntryId());
            if (inst.getResult().getType() != ConfigFlowResult.ResultType.CREATE_ENTRY) {
                throw new IllegalStateException("confirm 后应 CREATE_ENTRY，实际: "
                        + inst.getResult().getType()
                        + (inst.getResult().getReason() != null ? " reason=" + inst.getResult().getReason() : ""));
            }

            // 3. 验 entry(source=IMPORT_FLOW) + 设备加载
            ConfigEntry entry = getEntry();
            if (entry == null) {
                throw new IllegalStateException("entry 未落库: " + EXPECTED_UNIQUE_ID);
            }
            if (entry.getSource() != SourceType.IMPORT_FLOW) {
                throw new IllegalStateException("entry.source 应为 IMPORT_FLOW，实际: " + entry.getSource());
            }
            boolean deviceOk = waitForDevice(15);
            String summary = "import-flow 自包含 E2E 成功：uniqueId=" + entry.getUniqueId()
                    + ", source=" + entry.getSource() + (deviceOk ? "（设备+属性就绪）" : "（设备等待中超时）");
            log.info("[test-discovery] ✅ {}", summary);
            writeReport("SUCCESS", summary);
        } catch (Throwable e) {
            log.error("[test-discovery] import-flow 自包含 E2E 失败", e);
            fail("E2E 运行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== 辅助 ====================

    private boolean entryExists() {
        return getEntry() != null;
    }

    private ConfigEntry getEntry() {
        ConfigEntryRegistry reg = core.getEntryRegistry();
        return reg != null ? reg.getByUniqueId(EXPECTED_UNIQUE_ID) : null;
    }

    private void assertShowForm(ConfigFlowInstance inst, String expectedStep, String msg) {
        if (inst.getResult().getType() != ConfigFlowResult.ResultType.SHOW_FORM) {
            throw new IllegalStateException(msg + "（期望 SHOW_FORM，实际 " + inst.getResult().getType()
                    + "，reason=" + inst.getResult().getReason() + "）");
        }
        if (!expectedStep.equals(inst.getStepId())) {
            throw new IllegalStateException(msg + "（期望 step=" + expectedStep + "，实际 " + inst.getStepId() + "）");
        }
    }

    /**
     * 等待 import-flow 创建的仿真设备在线 + temperature 有值（经 getAllDevices 按 uniqueId 匹配）。
     */
    private boolean waitForDevice(int maxWaitSecs) {
        long deadline = System.currentTimeMillis() + maxWaitSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (DeviceBase d : integration.getAllDevices()) {
                if (d instanceof TestDiscoverySimulatedDevice
                        && d.getEntry() != null
                        && EXPECTED_UNIQUE_ID.equals(d.getEntry().getUniqueId())) {
                    TestDiscoverySimulatedDevice sim = (TestDiscoverySimulatedDevice) d;
                    boolean online = sim.getDeviceStatus() == DeviceStatus.NORMAL;
                    boolean hasTemp = sim.getAttrs().get("temperature") != null
                            && sim.getAttrs().get("temperature").getValue() != null;
                    if (online && hasTemp) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void fail(String reason) {
        log.error("[test-discovery] ❌ import-flow 自包含 E2E 失败: {}", reason);
        writeReport("FAIL", reason);
    }

    private void writeReport(String status, String summary) {
        try (PrintWriter w = new PrintWriter(new FileWriter(RESULT_FILE, false))) {
            w.println("test-discovery import-flow 自包含 E2E result");
            w.println("status: " + status);
            w.println("uniqueId: " + EXPECTED_UNIQUE_ID);
            w.println("summary: " + summary);
            w.println("timestamp_ms: " + System.currentTimeMillis());
        } catch (IOException e) {
            log.warn("[test-discovery] 写报告失败 {}: {}", RESULT_FILE, e.toString());
        }
    }
}

package com.ecat.integration.testdiscovery.zeroconf;

import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.ConfigFlow.ImportFlowPayload;

import com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow;
import com.ecat.integration.zeroconf.ZeroconfDiscoveryPayload;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link TestDiscoveryConfigFlow} 单测——多类发现编排（IMPORT_FLOW + ZEROCONF），各自落共享 confirm 步。
 * <p>验证两种 discovery handler（独立文件）经 flow 注册后行为正确。
 */
public class TestDiscoveryConfigFlowTest {

    private TestDiscoveryConfigFlow flow;
    private FlowContext ctx;

    @Before
    public void setUp() {
        flow = new TestDiscoveryConfigFlow();
        ctx = flow.getContext();
        ctx.setCoordinate("com.ecat:integration-test-discovery");
    }

    // ========== IMPORT_FLOW（ImportFlowDiscoveryHandler）==========

    @Test
    public void testImportFlowDiscovery_LandsOnConfirm() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-test-discovery", 1,
                "TestImportFlowDevice|IMPORTFLOW001|test-discovery import-flow IMPORTFLOW001");
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("应落地 confirm 步", "confirm", result.getStepId());
        assertEquals("uniqueId 派生（testdiscovery_importflow_ 前缀）",
                "testdiscovery_importflow_IMPORTFLOW001", ctx.getEntryUniqueId());
        assertEquals("model 预填", "TestImportFlowDevice", ctx.getEntryData("model"));
        assertEquals("sn 预填", "IMPORTFLOW001", ctx.getEntryData("sn"));
    }

    @Test
    public void testImportFlowDiscovery_EmptySn_Abort() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-test-discovery", 1,
                "TestImportFlowDevice|");
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);
        assertEquals("sn 空应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    @Test
    public void testImportFlowDiscovery_BadFormat_Abort() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-test-discovery", 1,
                "only-no-pipe");
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);
        assertEquals("格式错误应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    // ========== ZEROCONF（ZeroconfDiscoveryHandler）==========

    private ZeroconfDiscoveryPayload payload(String name, String model, String sn) {
        Map<String, String> txt = new HashMap<String, String>();
        if (model != null) {
            txt.put("model", model);
        }
        if (sn != null) {
            txt.put("sn", sn);
        }
        txt.put("vendor", "ECAT-Test");
        return new ZeroconfDiscoveryPayload("_ecat-test._tcp.local.", name,
                java.util.Collections.singletonList("192.168.1.50"), 18080, txt);
    }

    @Test
    public void testZeroconfDiscovery_LandsOnProbe() {
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.ZEROCONF,
                payload("TestDiscovery-zeroconf001", "TestDiscoveryDevice", "zeroconf001"));

        assertEquals("应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("应落地 probe 步（ZEROCONF 经 probe→confirm，非直达 confirm）",
                "probe", result.getStepId());
        assertEquals("uniqueId 应由 sn 派生（testdiscovery_ 前缀）",
                "testdiscovery_zeroconf001", ctx.getEntryUniqueId());
        assertEquals("model 预填", "TestDiscoveryDevice", ctx.getEntryData("model"));
        assertEquals("sn 预填", "zeroconf001", ctx.getEntryData("sn"));
        assertEquals("ip 预填（连接信息，供 probe 步探活）", "192.168.1.50", ctx.getEntryData("ip"));
    }

    @Test
    public void testZeroconfDiscovery_MissingSn_Abort() {
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.ZEROCONF,
                payload("n", "TestDiscoveryDevice", null));
        assertEquals("缺 sn 应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
        assertTrue("ABORT reason 应提示 TXT 缺 model/sn", result.getReason().contains("TXT 缺 model/sn"));
    }

    @Test
    public void testZeroconfDiscovery_EmptyTxt_Abort() {
        Map<String, String> empty = new HashMap<String, String>();
        ZeroconfDiscoveryPayload p = new ZeroconfDiscoveryPayload("_ecat-test._tcp.local.", "n",
                java.util.Collections.<String>emptyList(), 18080, empty);
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.ZEROCONF, p);
        assertEquals(ConfigFlowResult.ResultType.ABORT, result.getType());
    }
}

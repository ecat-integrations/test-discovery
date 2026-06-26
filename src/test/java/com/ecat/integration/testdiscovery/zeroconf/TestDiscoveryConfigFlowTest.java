package com.ecat.integration.testdiscovery.zeroconf;

import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.ConfigFlow.ImportFlowPayload;

import com.ecat.integration.testdiscovery.TestDiscoveryConfigFlow;
import com.ecat.integration.zeroconf.ZeroconfDiscoveryPayload;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link TestDiscoveryConfigFlow} 单测——多类发现编排 + ZEROCONF 各步（probe/credentials/confirm/probe_failed）行为。
 * <p>验证两种 discovery handler（独立文件）经 flow 注册后行为正确，以及 ZEROCONF 的探活→凭证→确认→(失败支路) 步骤流转。
 * <p>探活/凭证拉数据的 HTTP 契约见 {@link ProbeHttpClientTest}；本类聚焦步骤编排与 status/submit 区分。
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

    // ========== IMPORT_FLOW（ImportFlowDiscoveryHandler，payload = model|sn|name|account|password）==========

    @Test
    public void testImportFlowDiscovery_LandsOnConfirm_WithCredentials() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-test-discovery", 1,
                "TestImportFlowDevice|IMPORTFLOW001|test-discovery import-flow IMPORTFLOW001|admin|123");
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("应落地 confirm 步", "confirm", result.getStepId());
        assertEquals("uniqueId 派生（testdiscovery_importflow_ 前缀）",
                "testdiscovery_importflow_IMPORTFLOW001", ctx.getEntryUniqueId());
        assertEquals("model 预填", "TestImportFlowDevice", ctx.getEntryData("model"));
        assertEquals("sn 预填", "IMPORTFLOW001", ctx.getEntryData("sn"));
        assertEquals("account 预填（payload 携带）", "admin", ctx.getEntryData("account"));
        assertEquals("password 预填（payload 携带）", "123", ctx.getEntryData("password"));
        assertEquals("ip 预填（import 程序已知本机 ProbeHttpServer）", "127.0.0.1", ctx.getEntryData("ip"));
        assertEquals("port 预填", ProbeHttpServer.PORT, ctx.getEntryData("port"));
    }

    @Test
    public void testImportFlowDiscovery_EmptyPassword_Abort() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-test-discovery", 1,
                "TestImportFlowDevice|IMPORTFLOW001|name|admin|");
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);
        assertEquals("password 段空应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    @Test
    public void testImportFlowDiscovery_BadFormat_Abort() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-test-discovery", 1,
                "only-no-pipe");
        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);
        assertEquals("格式错误（<5 段）应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    // ========== ZEROCONF discovery（ZeroconfDiscoveryHandler）==========

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
        assertEquals("应落地 probe 步（ZEROCONF 经 probe→credentials→confirm）",
                "probe", result.getStepId());
        assertEquals("uniqueId 应由 sn 派生（testdiscovery_ 前缀）",
                "testdiscovery_zeroconf001", ctx.getEntryUniqueId());
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

    // ========== ZEROCONF 步骤流转（probe / probe_failed / credentials / confirm）==========

    /** 预填 ctx 连接信息（模拟 discovery 已落 probe 步的前置数据）。*/
    private void prefillProbeContext(String ip, int port) {
        ctx.setEntryData("ip", ip);
        ctx.setEntryData("port", port);
        ctx.setEntryData("model", ProbeHttpServer.MODEL);
        ctx.setEntryData("sn", ProbeHttpServer.SN);
    }

    @Test
    public void testProbe_Unreachable_LandsOnProbeFailed() {
        prefillProbeContext("127.0.0.1", 1); // 1 号端口无 server → 探活失败
        ConfigFlowResult result = flow.handleStep("probe", null);
        assertEquals("探活失败应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("探活失败应落 probe_failed 步", "probe_failed", result.getStepId());
    }

    @Test
    public void testProbe_MissingIpPort_Aborts() {
        // 未预填 ip/port（类型不符）→ 直接 ABORT（discovery 解析异常，非设备不可达）
        ConfigFlowResult result = flow.handleStep("probe", null);
        assertEquals("ip/port 缺失应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    @Test
    public void testProbe_LiveServer_StatusShowsSuccess_SubmitAdvancesToCredentials() {
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live probe 步骤测试", e);
            return;
        }
        try {
            prefillProbeContext("127.0.0.1", ProbeHttpServer.PORT);
            // status(null)：探活通过 → 停 probe 步展示设备信息
            ConfigFlowResult status = flow.handleStep("probe", null);
            assertEquals("探活通过 status 应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, status.getType());
            assertEquals("探活通过 status 应停 probe 步", "probe", status.getStepId());
            // submit(非null)：推进到 credentials
            Map<String, Object> submitInput = new HashMap<String, Object>();
            submitInput.put("probe_info", "x");
            ConfigFlowResult submit = flow.handleStep("probe", submitInput);
            assertEquals("探活通过 submit 应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, submit.getType());
            assertEquals("探活通过 submit 应推进到 credentials", "credentials", submit.getStepId());
        } finally {
            server.stop();
        }
    }

    @Test
    public void testProbeFailed_StatusReplays_SubmitAborts() {
        // status(null)：重放失败提示（不 ABORT——重开向导不误结束）
        ConfigFlowResult status = flow.handleStep("probe_failed", null);
        assertEquals("probe_failed status 应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, status.getType());
        assertEquals("probe_failed", status.getStepId());
        // submit(非null)：用户点【下一步】→ ABORT 结束
        Map<String, Object> submitInput = new HashMap<String, Object>();
        submitInput.put("probe_failed", "设备无法访问");
        ConfigFlowResult submit = flow.handleStep("probe_failed", submitInput);
        assertEquals("probe_failed submit 应 ABORT", ConfigFlowResult.ResultType.ABORT, submit.getType());
    }

    @Test
    public void testCredentials_StatusReplays() {
        ConfigFlowResult status = flow.handleStep("credentials", null);
        assertEquals("credentials status 应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, status.getType());
        assertEquals("credentials", status.getStepId());
    }

    @Test
    public void testCredentials_SubmitEmpty_ShowsErrors() {
        Map<String, Object> empty = new HashMap<String, Object>();
        ConfigFlowResult result = flow.handleStep("credentials", empty);
        assertEquals("空提交应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("空校验应停 credentials 步", "credentials", result.getStepId());
        assertNotNull("应有 errors", result.getErrors());
        assertTrue("应有 account 错误", result.getErrors().containsKey("account"));
        assertTrue("应有 password 错误", result.getErrors().containsKey("password"));
    }

    @Test
    public void testCredentials_SubmitValid_AdvancesToConfirm_StoresCreds() {
        // 凭证步当场验证 → 需 live ProbeHttpServer（/data BasicAuth admin/123）
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live 凭证验证测试", e);
            return;
        }
        try {
            prefillProbeContext("127.0.0.1", ProbeHttpServer.PORT);
            Map<String, Object> input = new HashMap<String, Object>();
            input.put("account", "admin");
            input.put("password", "123");
            ConfigFlowResult result = flow.handleStep("credentials", input);
            assertEquals("有效凭证（当场验证通过）应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
            assertEquals("有效凭证应推进 confirm 步", "confirm", result.getStepId());
            assertEquals("account 应存入 entryData", "admin", ctx.getEntryData("account"));
            assertEquals("password 应存入 entryData", "123", ctx.getEntryData("password"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testCredentials_SubmitInvalid_ReplaysWithError() {
        // 错密码 → /data 返 401 → 当场验证失败 → 重放凭证步（不放行错误凭证入网）
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live 凭证验证测试", e);
            return;
        }
        try {
            prefillProbeContext("127.0.0.1", ProbeHttpServer.PORT);
            Map<String, Object> input = new HashMap<String, Object>();
            input.put("account", "admin");
            input.put("password", "wrong");
            ConfigFlowResult result = flow.handleStep("credentials", input);
            assertEquals("无效凭证应 SHOW_FORM 重放（不放行入网）", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
            assertEquals("无效凭证应停 credentials 步", "credentials", result.getStepId());
            assertNotNull("应有 errors", result.getErrors());
            assertTrue("应有 account 凭证错误", result.getErrors().containsKey("account"));
            assertTrue("应有 password 凭证错误", result.getErrors().containsKey("password"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testCredentials_Unreachable_ReplaysWithError() {
        // 设备不可达（1 号端口无 server）→ /data 拉取失败 → 当场验证失败 → 重放（不误判放行）
        prefillProbeContext("127.0.0.1", 1);
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("account", "admin");
        input.put("password", "123");
        ConfigFlowResult result = flow.handleStep("credentials", input);
        assertEquals("设备不可达应 SHOW_FORM 重放（不误判凭证有效放行）",
                ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("不可达应停 credentials 步", "credentials", result.getStepId());
        assertNotNull("应有 errors", result.getErrors());
        assertTrue("应有 account 错误", result.getErrors().containsKey("account"));
    }

    @Test
    public void testConfirm_StatusReplays_DoesNotCreateEntry() {
        // confirm status(null)：重放汇总（不调 createEntry——避免 getStatus 副作用）
        ctx.setEntryData("model", "TestDiscoveryDevice");
        ConfigFlowResult status = flow.handleStep("confirm", null);
        assertEquals("confirm status 应 SHOW_FORM 汇总（不 createEntry）",
                ConfigFlowResult.ResultType.SHOW_FORM, status.getType());
        assertEquals("confirm", status.getStepId());
        // createEntry 路径需 core/registry，由 E2E（ImportFlowTestDriver）覆盖，此处不测
    }

    // ========== reconfigure（已入网设备改凭证，只改 account/password）==========

    @Test
    public void testReconfigure_Registered() {
        assertTrue("应注册了 reconfigure 步（注册后 reconfigure 端点可用，修复'无可供 reconfigure 的流程'两头堵）",
                flow.hasReconfigureStep());
    }

    @Test
    public void testReconfigure_Status_ShowsForm_PrefillsAccount() {
        // reconfigure 启动时 ConfigFlowService 把 entry.data 预填进 ctx（含现有 account）
        ctx.setEntryData("account", "old-admin");
        ConfigFlowResult status = flow.executeReconfigureStep("entry-xyz", null);
        assertEquals("reconfigure status 应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, status.getType());
        assertEquals("reconfigure", status.getStepId());
        AbstractConfigItem<?> accountField = findField(status.getSchema(), "account");
        assertNotNull("schema 应含 account 字段", accountField);
        assertEquals("account 应预填当前账号（defaultValue）", "old-admin", accountField.getDefaultValue());
    }

    @Test
    public void testReconfigure_SubmitEmpty_ShowsErrors() {
        ctx.setEntryData("account", "old-admin");
        ConfigFlowResult result = flow.executeReconfigureStep("entry-xyz", new HashMap<String, Object>());
        assertEquals("空提交应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("空校验应停 reconfigure 步", "reconfigure", result.getStepId());
        assertNotNull("应有 errors", result.getErrors());
        assertTrue("应有 account 错误", result.getErrors().containsKey("account"));
        assertTrue("应有 password 错误", result.getErrors().containsKey("password"));
    }

    @Test
    public void testReconfigure_SubmitValid_ReturnsCreateEntry_StoresCreds() {
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live 凭证验证测试", e);
            return;
        }
        try {
            prefillProbeContext("127.0.0.1", ProbeHttpServer.PORT);
            ctx.setEntryData("account", "old-admin");
            Map<String, Object> input = new HashMap<String, Object>();
            input.put("account", "admin");
            input.put("password", "123");
            ConfigFlowResult result = flow.executeReconfigureStep("entry-xyz", input);
            assertEquals("有效新凭证（当场验证通过）应 CREATE_ENTRY（sourceType=RECONFIGURE 自动转 updateEntry）",
                    ConfigFlowResult.ResultType.CREATE_ENTRY, result.getType());
            assertEquals("account 应更新为新值", "admin", ctx.getEntryData("account"));
            assertEquals("password 应更新为新值", "123", ctx.getEntryData("password"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testReconfigure_SubmitInvalid_ReplaysWithError() {
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live 凭证验证测试", e);
            return;
        }
        try {
            prefillProbeContext("127.0.0.1", ProbeHttpServer.PORT);
            ctx.setEntryData("account", "old-admin");
            Map<String, Object> input = new HashMap<String, Object>();
            input.put("account", "admin");
            input.put("password", "wrong");
            ConfigFlowResult result = flow.executeReconfigureStep("entry-xyz", input);
            assertEquals("无效新凭证应 SHOW_FORM 重放（不更新）", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
            assertEquals("reconfigure", result.getStepId());
            assertNotNull("应有 errors", result.getErrors());
            assertTrue("应有 password 凭证错误", result.getErrors().containsKey("password"));
            assertEquals("无效凭证不应改写 account", "old-admin", ctx.getEntryData("account"));
        } finally {
            server.stop();
        }
    }

    /** 从 schema 找指定 key 的字段（用于断言 reconfigure 预填 defaultValue）。*/
    private static AbstractConfigItem<?> findField(ConfigSchema schema, String key) {
        for (AbstractConfigItem<?> f : schema.getFields()) {
            if (key.equals(f.getKey())) {
                return f;
            }
        }
        return null;
    }
}

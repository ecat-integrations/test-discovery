package com.ecat.integration.testdiscovery.zeroconf;

import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.FlowContext;

import com.ecat.integration.testdiscovery.TestDiscoveryIntegration;
import com.ecat.integration.zeroconf.ZeroconfDiscoveryPayload;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * zeroconf 消费路径验证（配合 integration-zeroconf I-21 新投递形态）。
 *
 * <p>I-21 后 jmdns serviceResolved 只做 O(1) 快照：从 ServiceInfo 构
 * {@link ZeroconfDiscoveryPayload}（type/name/addresses/port/TXT）→ submitDiscoveryEvent 投
 * module:zeroconf 车道 → worker 侧 triggerDiscoveryFlows → startDiscoveryFlow(ZEROCONF, payload)
 * → 本集成 {@link ZeroconfDiscoveryHandler#discovery}。本测锁定消费端在<b>快照载荷形态</b>下闭环：
 * 广播方真实常量（{@link ProbeHttpServer} MODEL/SN/PORT）经 handler 解析后，uniqueId 必须
 * 等于 {@link TestDiscoveryIntegration#EXPECTED_ZEROCONF_UNIQUE_ID}（闭环约定），地址列表
 * 含 IPv6 时挑 IPv4（jmdns 快照可能双栈）。无需多播，纯载荷级确定性验证。
 */
public class ZeroconfConsumptionPathTest {

    /** 模拟 I-21 快照形态：TXT = 广播方真实常量；地址列表混入 IPv6（jmdns getHostAddresses 可能双栈）。*/
    private static ZeroconfDiscoveryPayload snapshotPayload() {
        Map<String, String> txt = new HashMap<String, String>();
        txt.put("model", ProbeHttpServer.MODEL);
        txt.put("sn", ProbeHttpServer.SN);
        txt.put("vendor", ProbeHttpServer.VENDOR);
        return new ZeroconfDiscoveryPayload("_ecat-test._tcp.local.", "TestDiscovery-zeroconf001-0",
                Arrays.asList("fe80::1a2b:3c4d", "127.0.0.1"), ProbeHttpServer.PORT, txt);
    }

    @Test
    public void snapshotPayload_ConsumedByHandler_LandsOnProbeWithLoopConstants() {
        ZeroconfDiscoveryHandler handler = new ZeroconfDiscoveryHandler();
        FlowContext ctx = new FlowContext("com.ecat:integration-test-discovery");

        ConfigFlowResult result = handler.discovery(snapshotPayload(), ctx);

        assertEquals("快照载荷应落 probe 步（SHOW_FORM 等用户）",
                ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("probe 步 id", "probe", result.getStepId());
        assertEquals("uniqueId 闭环：广播 TXT sn → handler 前缀派生 = 集成预期常量",
                TestDiscoveryIntegration.EXPECTED_ZEROCONF_UNIQUE_ID, ctx.getEntryUniqueId());
        assertEquals("model 取自快照 TXT", ProbeHttpServer.MODEL, ctx.getEntryData("model"));
        assertEquals("sn 取自快照 TXT", ProbeHttpServer.SN, ctx.getEntryData("sn"));
        assertEquals("地址列表含 IPv6 时挑 IPv4（probe 步连接用）", "127.0.0.1", ctx.getEntryData("ip"));
        assertEquals("port = 广播声明端口（与探活应答同真相源）",
                ProbeHttpServer.PORT, ctx.getEntryData("port"));
        assertNotNull("probe 步 schema 应已构建", result.getSchema());
    }

    /** 广播方与 handler 的唯一闭环锚点防漂移：TXT sn 常量必须能拼出预期 uniqueId（两常量分处两个文件）。*/
    @Test
    public void broadcasterSnConstant_MatchesHandlerUniqueIdDerivation() {
        assertEquals("广播 TXT sn ↔ EXPECTED_ZEROCONF_UNIQUE_ID 闭环约定（改任一侧须同步）",
                TestDiscoveryIntegration.EXPECTED_ZEROCONF_UNIQUE_ID,
                ZeroconfDiscoveryHandler.UNIQUE_ID_PREFIX + ProbeHttpServer.SN);
    }
}

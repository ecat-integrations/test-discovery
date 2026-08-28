package com.ecat.integration.testdiscovery.zeroconf;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.State.AttrState;

import org.junit.Test;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link TestDiscoverySimulatedDevice} 轮询契约单测（W1-2：getScheduledExecutor 直调迁 HttpPolling SDK）。
 *
 * <p>锁定两条迁移语义：
 * <ul>
 *   <li><b>状态迁移到 round 完成点</b>：NORMAL/OFFLINE 由一轮拉取的实际结果决定
 *       （成功→属性上报+NORMAL；401/不可达/解析失败→OFFLINE，不编造数据），start() 不再乐观置 NORMAL；</li>
 *   <li><b>轮询链由 SDK 自排</b>：start() 起链后首拍（delay 0）无需调用方参与即完成（CountDownLatch
 *       确定性同步，禁 sleep 等待）；句柄经 RemovalHost 绑设备生命周期，cancelManagedTasks 统一收尾。</li>
 * </ul>
 *
 * <p>live 端点用随机空闲端口起 {@link ProbeHttpServer}（{@link ProbeHttpServerRoutePolicyTest} 同法，
 * 不与常驻 demo 的固定 18081 冲突，必跑）；死端点用 1 号端口（loopback 连接拒绝）。
 */
public class TestDiscoverySimulatedDeviceTest {

    /** live 受探 server + 端口（ProbeHttpServer 无端口 getter，占坑取随机端口后包内构造持有）。 */
    private static final class LiveProbeServer implements AutoCloseable {
        final ProbeHttpServer server;
        final int port;

        private LiveProbeServer(int port) {
            this.port = port;
            this.server = new ProbeHttpServer(port);
            this.server.start();
        }

        static LiveProbeServer start() throws Exception {
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            } // 占坑 socket 必须先关，undertow 才能绑同一端口（try 内直接 bind = 自己撞自己）
            return new LiveProbeServer(port);
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    /** 造 entry：凭证/地址齐全（与 zeroconf/import-flow flow confirm 后落 entry.data 的形态一致）。 */
    private static ConfigEntry entryWithCreds(String ip, int port) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("account", ProbeHttpServer.AUTH_ACCOUNT);
        data.put("password", ProbeHttpServer.AUTH_PASSWORD);
        data.put("ip", ip);
        data.put("port", port);
        return new ConfigEntry.Builder()
                .entryId("test-entry-" + System.nanoTime())
                .coordinate("com.ecat:integration-test-discovery")
                .uniqueId("testdiscovery_test-sim-device")
                .title("sim-device-test")
                .data(data)
                .build();
    }

    /** 缺凭证 entry（异常形态：flow 应已写入，缺失属配置异常）。 */
    private static ConfigEntry entryWithoutCreds() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("ip", "127.0.0.1");
        return new ConfigEntry.Builder()
                .entryId("test-entry-nocreds")
                .coordinate("com.ecat:integration-test-discovery")
                .uniqueId("testdiscovery_test-sim-nocreds")
                .title("sim-device-nocreds")
                .data(data)
                .build();
    }

    /**
     * 轮完成观测设备：包内覆写 pullRound 挂 CountDownLatch——同步等待「事件已发生」，
     * 非 sleep 猜测（testing.md 确定性异步测试纪律）。
     */
    private static final class RoundProbeDevice extends TestDiscoverySimulatedDevice {
        final CountDownLatch firstRoundDone = new CountDownLatch(1);

        RoundProbeDevice(ConfigEntry entry) {
            super(entry);
        }

        @Override
        CompletableFuture<Boolean> pullRound() {
            CompletableFuture<Boolean> round = super.pullRound();
            round.whenComplete((result, failure) -> firstRoundDone.countDown());
            return round;
        }
    }

    /** 建设备并走对位框架的顺序：init → markReady（打开 publish 硬门禁）→ start（起轮询链）。 */
    private static RoundProbeDevice startedDevice(ConfigEntry entry) {
        RoundProbeDevice device = new RoundProbeDevice(entry);
        device.init();
        device.markReady();
        device.start();
        return device;
    }

    @Test
    public void start_ArmsSdkPolling_FirstRoundCompletesWithoutCallerInvolvement() throws Exception {
        try (LiveProbeServer live = LiveProbeServer.start()) {
            RoundProbeDevice device = startedDevice(entryWithCreds("127.0.0.1", live.port));
            try {
                assertTrue("SDK 轮询链首拍（delay 0）应在 10s 内完成一轮，无需调用方参与",
                        device.firstRoundDone.await(10, TimeUnit.SECONDS));
                assertEquals("首轮拉到数据 → NORMAL（状态在 round 完成点，非 start 乐观置位）",
                        DeviceStatus.NORMAL, device.getDeviceStatus());
                AttrState<?> temp = device.getAttrs().get("temperature").getState();
                assertNotNull("temperature 应已上报", temp);
                assertNotNull(temp.getValue());
            } finally {
                device.cancelManagedTasks(); // RemovalHost sweep：拆卸 SDK 轮询链
            }
        }
    }

    @Test
    public void roundSuccess_UpdatesAttrsAndSetsStatusNormal() throws Exception {
        try (LiveProbeServer live = LiveProbeServer.start()) {
            RoundProbeDevice device = startedDevice(entryWithCreds("127.0.0.1", live.port));
            try {
                device.cancelManagedTasks(); // 拆链：本测显式驱动单轮，不受周期链并发轮干扰
                CompletableFuture<Boolean> round = device.pullRound();
                assertTrue("成功轮应返 true（业务成功）", round.get(10, TimeUnit.SECONDS));
                assertEquals("拉到数据 → NORMAL", DeviceStatus.NORMAL, device.getDeviceStatus());
                Float temp = (Float) device.getAttrs().get("temperature").getState().getValue();
                Float hum = (Float) device.getAttrs().get("humidity").getState().getValue();
                Float rssi = (Float) device.getAttrs().get("rssi").getState().getValue();
                assertNotNull(temp);
                assertTrue("temperature 应在仿真范围 20~30°C: " + temp, temp >= 20f && temp <= 30f);
                assertTrue("humidity 应在仿真范围 35~65%: " + hum, hum >= 35f && hum <= 65f);
                assertTrue("rssi 应在仿真范围 -65~-45: " + rssi, rssi >= -65f && rssi <= -45f);
            } finally {
                device.cancelManagedTasks();
            }
        }
    }

    @Test
    public void roundFailure_Unreachable_SetsStatusOffline_NoFabricatedData() throws Exception {
        RoundProbeDevice device = startedDevice(entryWithCreds("127.0.0.1", 1));
        try {
            device.cancelManagedTasks(); // 拆链：只留本测显式驱动的一轮
            CompletableFuture<Boolean> round = device.pullRound();
            assertFalse("失败轮（不可达）应返 false（业务失败）", round.get(10, TimeUnit.SECONDS));
            assertEquals("不可达 → OFFLINE（round 完成点）", DeviceStatus.OFFLINE, device.getDeviceStatus());
            assertNull("失败轮不得编造数据（temperature 保持未上报）",
                    device.getAttrs().get("temperature").getState());
        } finally {
            device.cancelManagedTasks();
        }
    }

    @Test
    public void missingCredentials_NoPolling_StatusStaysOffline() {
        TestDiscoverySimulatedDevice device = new TestDiscoverySimulatedDevice(entryWithoutCreds());
        device.init();
        device.start();
        assertEquals("凭证/地址缺失属配置异常 → OFFLINE 且不起轮询",
                DeviceStatus.OFFLINE, device.getDeviceStatus());
    }
}

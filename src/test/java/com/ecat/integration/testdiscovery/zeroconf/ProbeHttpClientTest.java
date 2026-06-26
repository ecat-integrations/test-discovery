package com.ecat.integration.testdiscovery.zeroconf;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProbeHttpClient} 单测——探活（{@link ProbeHttpClient#probe}）+ 凭证拉数据（{@link ProbeHttpClient#pullData}）的 HTTP 契约。
 * <p>真实起 {@link ProbeHttpServer}（受探方 + /data 认证端点），验证：
 * 探活应答 model/sn 匹配→true、不匹配→false、不可达→false；
 * 凭证拉数据正确凭证→SensorData、错误凭证→null(401)、不可达→null。
 * <p>live 测试在 {@link ProbeHttpServer#PORT}（18081）已被占用（如 core 正运行）时用 {@link Assume} 跳过，避免误红。
 */
public class ProbeHttpClientTest {

    @Test
    public void testProbe_LiveServer_Match_ReturnsTrue() {
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live probe 测试", e);
            return;
        }
        try {
            assertTrue("model/sn 与受探 server 应答一致 → true",
                    ProbeHttpClient.probe("127.0.0.1", ProbeHttpServer.PORT, ProbeHttpServer.MODEL, ProbeHttpServer.SN));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testProbe_LiveServer_ModelMismatch_ReturnsFalse() {
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live probe 测试", e);
            return;
        }
        try {
            assertFalse("model 不符 → false",
                    ProbeHttpClient.probe("127.0.0.1", ProbeHttpServer.PORT, "WrongModel", ProbeHttpServer.SN));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testProbe_Unreachable_ReturnsFalse() {
        // 1 号端口（无 server）→ 连接拒绝 → false（无外部依赖，必跑）
        assertFalse("不可达端口 → false",
                ProbeHttpClient.probe("127.0.0.1", 1, ProbeHttpServer.MODEL, ProbeHttpServer.SN));
    }

    @Test
    public void testPullData_LiveServer_ValidCreds_ReturnsData() {
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live pullData 测试", e);
            return;
        }
        try {
            ProbeHttpClient.SensorData data = ProbeHttpClient.pullData("127.0.0.1", ProbeHttpServer.PORT,
                    ProbeHttpServer.AUTH_ACCOUNT, ProbeHttpServer.AUTH_PASSWORD);
            assertNotNull("正确凭证应拉到数据", data);
            assertTrue("temperature 应在仿真范围 20~30°C: " + data.temperature,
                    data.temperature >= 20f && data.temperature <= 30f);
            assertTrue("humidity 应在仿真范围 35~65%: " + data.humidity,
                    data.humidity >= 35f && data.humidity <= 65f);
            assertTrue("rssi 应在仿真范围 -65~-45: " + data.rssi,
                    data.rssi >= -65f && data.rssi <= -45f);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testPullData_LiveServer_WrongCreds_ReturnsNull() {
        ProbeHttpServer server = new ProbeHttpServer();
        try {
            server.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("端口 " + ProbeHttpServer.PORT + " 被占用，跳过 live pullData 测试", e);
            return;
        }
        try {
            assertNull("错误密码应 401 → null",
                    ProbeHttpClient.pullData("127.0.0.1", ProbeHttpServer.PORT,
                            ProbeHttpServer.AUTH_ACCOUNT, "wrong-password"));
            assertNull("错误账号应 401 → null",
                    ProbeHttpClient.pullData("127.0.0.1", ProbeHttpServer.PORT,
                            "wrong-account", ProbeHttpServer.AUTH_PASSWORD));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testPullData_Unreachable_ReturnsNull() {
        // 1 号端口（无 server）→ 连接拒绝 → null（无外部依赖，必跑）
        assertNull("不可达端口 → null",
                ProbeHttpClient.pullData("127.0.0.1", 1, ProbeHttpServer.AUTH_ACCOUNT, ProbeHttpServer.AUTH_PASSWORD));
    }
}

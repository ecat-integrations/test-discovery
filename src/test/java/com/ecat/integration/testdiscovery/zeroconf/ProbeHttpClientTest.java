package com.ecat.integration.testdiscovery.zeroconf;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProbeHttpClient} 单测——探活客户端的 HTTP 契约。
 * <p>真实起 {@link ProbeHttpServer}（受探方），验证：应答 model/sn 匹配→true；不匹配→false；不可达→false。
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
}

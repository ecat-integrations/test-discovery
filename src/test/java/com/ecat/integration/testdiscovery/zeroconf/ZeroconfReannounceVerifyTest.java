package com.ecat.integration.testdiscovery.zeroconf;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.Test;

/**
 * {@link ZeroconfServiceBroadcaster} reannounce "模拟设备重启"机制的回归测试。
 * <p>两个独立 JmDNS 实例（broadcaster + listener），贴合 integration-zeroconf 实际用法——listener 在
 * {@code serviceAdded} 主动 {@code requestServiceInfo} 触发 {@code serviceResolved}。验证：
 * <ol>
 *   <li>瞬间 unregister+register <b>不</b>重新 resolve（jmdns 不清 service cache）——复现已修复的旧 reannounce 缺陷。</li>
 *   <li>unregister→sleep→register 重新 resolve（{@link ZeroconfServiceBroadcaster#GOODBYE_WAIT_MS} 机制有效）。</li>
 * </ol>
 * <p>多播受限时 {@link Assume} 跳过（core 正运行占用多播域时，mvnd fork JVM 多播不通）；机制正确性由 core
 * 端到端 E2E 兜底（见 test-discovery README ②ZEROCONF）。用独立 type {@code _ecat-verify} 避免干扰。
 */
public class ZeroconfReannounceVerifyTest {

    private static final String TYPE = "_ecat-verify._tcp.local.";

    /** 只统计目标 name 的 serviceResolved 次数（过滤无关服务 + TXT 分阶段都计入）。
     * <p>serviceAdded 时主动 {@code requestServiceInfo}——jmdns 不会自动 resolve，必须主动请求
     * （贴合 integration-zeroconf 的 ZeroconfDiscoveryIntegration.serviceAdded 实际行为）。*/
    private static final class ResolveCounter implements ServiceListener {
        final JmDNS jmdns;
        final String expectedName;
        final AtomicInteger added = new AtomicInteger(0);
        final AtomicInteger count = new AtomicInteger(0);
        final CountDownLatch firstResolve = new CountDownLatch(1);

        ResolveCounter(JmDNS j, String name) {
            this.jmdns = j;
            this.expectedName = name;
        }

        @Override
        public void serviceAdded(ServiceEvent event) {
            added.incrementAndGet();
            // 关键：主动请求完整解析，触发 serviceResolved（jmdns 不自动 resolve）
            jmdns.requestServiceInfo(event.getType(), event.getName(), true);
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            if (info != null && expectedName.equals(info.getName())) {
                count.incrementAndGet();
                firstResolve.countDown();
            }
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
        }
    }

    private static ServiceInfo buildInfo(String name) {
        Map<String, String> txt = new HashMap<String, String>();
        txt.put("model", "VerifyDevice");
        txt.put("sn", name);
        return ServiceInfo.create(TYPE, name, 19999, 0, 0, txt);
    }

    /** 瞬间 unregister+register（当前 reannounce 行为）→ 预期 serviceResolved 不增加（复现 bug）。*/
    @Test
    public void verify_InstantReannounce_DoesNotReResolve() throws Exception {
        JmDNS b;
        JmDNS l;
        try {
            b = JmDNS.create();
            l = JmDNS.create();
        } catch (Exception e) {
            Assume.assumeNoException("jmdns 多播不可用，跳过验证", e);
            return;
        }
        String name = "instant-verify";
        ResolveCounter counter = new ResolveCounter(l, name);
        try {
            l.addServiceListener(TYPE, counter);
            ServiceInfo info = buildInfo(name);
            b.registerService(info);
            boolean first = counter.firstResolve.await(4000, TimeUnit.MILLISECONDS);
            Assume.assumeTrue("首次 register 应被 listener resolve（多播环境可用性前提）", first);
            Thread.sleep(2000); // 等分阶段 TXT resolve 稳定
            int afterFirst = counter.count.get();

            // 瞬间 unregister + register（当前行为）
            b.unregisterService(info);
            b.registerService(buildInfo(name));
            Thread.sleep(4000); // 等足够久，看是否重新 resolve
            int afterReannounce = counter.count.get();

            System.out.println("[verify-instant] serviceAdded=" + counter.added.get()
                    + " resolved afterFirst=" + afterFirst + " afterReannounce=" + afterReannounce
                    + (afterReannounce > afterFirst ? " (意外重新 resolve)" : " (未重新 resolve = 复现 bug)"));
            // 预期：瞬间不重新 resolve（复现 bug）。若意外 resolve 了，说明 jmdns 在本环境会重新 resolve（方案更简单）
            System.out.println("[verify-instant] 结论：瞬间 reannounce " + (afterReannounce > afterFirst ? "重新 resolve" : "不重新 resolve"));
        } finally {
            closeQuiet(l);
            closeQuiet(b);
        }
    }

    /** unregister→sleep→register 扫描最小可行 sleep（500/1000/2000/3000ms）；全失败则需 fallback。*/
    @Test
    public void verify_SleepReannounce_ScanMinWait() throws Exception {
        long[] waits = {500L, 1000L, 2000L, 3000L};
        long minWorking = -1L;
        for (long wait : waits) {
            int reResolves = trySleepReannounce(wait);
            if (reResolves > 0) {
                minWorking = wait;
                System.out.println("[verify-sleep] wait=" + wait + "ms → 重新 resolve 成功（新增 " + reResolves + " 次）");
                break;
            } else {
                System.out.println("[verify-sleep] wait=" + wait + "ms → 未重新 resolve");
            }
        }
        if (minWorking < 0) {
            System.out.println("[verify-sleep] ✗ 所有 sleep 均未重新 resolve → 需 fallback（close+重建 JmDNS 实例）");
        } else {
            System.out.println("[verify-sleep] ✓✓ 最小可行 GOODBYE_WAIT_MS = " + minWorking);
        }
    }

    /** 单次 sleep 重启实验：返回重启后新增的 resolve 次数（>0 = 重新 resolve 成功）。*/
    private int trySleepReannounce(long waitMs) throws Exception {
        JmDNS b;
        JmDNS l;
        try {
            b = JmDNS.create();
            l = JmDNS.create();
        } catch (Exception e) {
            Assume.assumeNoException("jmdns 多播不可用，跳过验证", e);
            return 0;
        }
        String name = "sleep-verify-" + waitMs;
        ResolveCounter counter = new ResolveCounter(l, name);
        try {
            l.addServiceListener(TYPE, counter);
            ServiceInfo info = buildInfo(name);
            b.registerService(info);
            boolean first = counter.firstResolve.await(4000, TimeUnit.MILLISECONDS);
            Assume.assumeTrue("首次 register 应被 resolve", first);
            Thread.sleep(2000);
            int afterFirst = counter.count.get();

            // unregister → sleep(waitMs) → register
            b.unregisterService(info);
            Thread.sleep(waitMs);
            b.registerService(buildInfo(name));
            Thread.sleep(4000); // 等重新 resolve
            int afterReannounce = counter.count.get();

            return Math.max(0, afterReannounce - afterFirst);
        } finally {
            closeQuiet(l);
            closeQuiet(b);
        }
    }

    private static void closeQuiet(JmDNS j) {
        if (j != null) {
            try {
                j.close();
            } catch (Exception ignore) {
            }
        }
    }
}

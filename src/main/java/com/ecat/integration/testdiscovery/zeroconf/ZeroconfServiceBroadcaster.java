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

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * zeroconf 闭环的<b>广播方</b>——长期常驻 + 周期重新广播一个 mDNS 测试服务。
 *
 * <p><b>单一职责</b>：jmdns 生命周期 + ServiceInfo 构造 + 周期重新广播。不关心 flow / 设备 / 结果。
 *
 * <p><b>为什么常驻 + 周期重广播</b>：被发现的"设备"（即本服务）即使已被添加，也持续周期广播，
 * <b>刻意制造"已添加设备重复广播"场景</b>来压测 core 的去重鲁棒性（R5/R12）。这是 zeroconf 鲁棒性验证的核心——
 * 系统必须能长期、反复地正确忽略已添加设备的重复广播（不产生重复 entry、不堆积孤儿 flow、不刷 WARN）。
 *
 * <p><b>归属</b>：由 {@link com.ecat.integration.testdiscovery.TestDiscoveryIntegration} 在 onLoad 长期持有
 * （与 {@link ProbeHttpServer} 同级），<b>不随单次 E2E 关闭</b>。生命周期 = 集成生命周期。
 *
 * <p>端口与 TXT（model/sn/vendor）一律取自 {@link ProbeHttpServer} 常量——被发现的"设备"即该 HTTP server，
 * 广播声明与探活应答共用同一真相源，必然吻合。
 *
 * <p><b>周期重新广播机制（换 service name）</b>：每周期 unregister 旧 name → 等 goodbye({@link #GOODBYE_WAIT_MS})
 * → register <b>新 name</b>（{@code SERVICE_NAME-<seq>}）。jmdns 对"新 name 服务"必然触发 listener
 * serviceResolved（同 name 持续广播则 listener cache 命中、不重复 resolve——实测瞬间 / unregister+sleep /
 * close+create 在<b>同 name</b> 下都不稳定）；故靠换 name 让 listener 每周期重新发现。TXT {@code sn} 不变 →
 * uniqueId 不变 → 已添加走 R5/R12 去重（兑现"重复发现"压测意图），已删除重新进 pending（解决删除后无法重发现）。
 *
 * @author coffee
 */
public class ZeroconfServiceBroadcaster {

    private static final String SERVICE_TYPE = "_ecat-test._tcp.local.";
    private static final String SERVICE_NAME = "TestDiscovery-zeroconf001";
    /** 周期重新广播间隔（秒）——每周期换新 service name 触发重新发现（已添加→R5/R12 去重压测；已删除→重新进 pending）。*/
    static final long REANNOUNCE_INTERVAL_SEC = 60L;
    /** unregister 旧 name 后等待 goodbye 传播的时长（ms），再 register 新 name。
     * <p>重新 resolve 靠"新 name"（非 goodbye 清 cache），此等待仅缓冲让旧 name 的 goodbye 发出、避免新旧
     * announce 重叠；2000ms 实测充分。*/
    private static final long GOODBYE_WAIT_MS = 2000L;

    private final Log log = LogFactory.getLogger(getClass());
    private volatile boolean running = false;
    private JmDNS jmdns;
    private ServiceInfo serviceInfo;
    private ScheduledExecutorService scheduler;
    /** service name 自增序号——每次广播用新 name（{@code SERVICE_NAME-<seq>}），让 listener 把它当新服务重新 resolve。*/
    private final AtomicLong nameSeq = new AtomicLong();

    /**
     * 启动广播：注册测试服务 + 起周期重新广播任务。
     *
     * @throws Exception jmdns 创建或注册失败（严格模式：不吞，由调用方决定）
     */
    public void start() throws Exception {
        jmdns = JmDNS.create();
        serviceInfo = buildServiceInfo();
        jmdns.registerService(serviceInfo);
        running = true;
        log.info("[test-discovery] zeroconf 已广播 mDNS 服务: {} port={} (TXT model={}/sn={})",
                SERVICE_TYPE, ProbeHttpServer.PORT, ProbeHttpServer.MODEL, ProbeHttpServer.SN);

        // 周期重新广播：长期常驻 + 定期刷新，持续压测"已添加设备重复广播"的去重鲁棒性
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-discovery-zeroconf-broadcast");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::reannounce, REANNOUNCE_INTERVAL_SEC, REANNOUNCE_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("[test-discovery] zeroconf 周期重新广播已启动，间隔 {}s（持续验证已添加设备重复广播的去重鲁棒性）",
                REANNOUNCE_INTERVAL_SEC);
    }

    /**
     * 周期重新广播：unregister 旧 name → 等 goodbye 传播 → register 新 name（同实例）。
     * <p>关键：每次用<b>新 service name</b>（{@code SERVICE_NAME-<seq>}，见 {@link #buildServiceInfo}）——jmdns 对
     * "新 name 服务"必然触发 listener {@code serviceResolved}（同 name 持续广播则 listener cache 命中、不重复
     * resolve；实测无论 unregister+sleep 还是 close+create 在同 name 下都不稳定）。换 name 让 listener 当新服务
     * 重新发现；TXT {@code sn} 不变 → uniqueId 不变 → 已添加走 R5/R12 去重，已删除重新进 pending。
     */
    private void reannounce() {
        if (!running) {
            return;
        }
        try {
            ServiceInfo old = serviceInfo;
            ServiceInfo fresh = buildServiceInfo(); // 新 name（jmdns 对新 name 必然 resolve）
            log.info("[test-discovery] zeroconf reannounce[1/3 unregister]: {}（注销旧 name）",
                    old != null ? old.getName() : "null");
            if (old != null) {
                jmdns.unregisterService(old);
            }
            log.info("[test-discovery] zeroconf reannounce[2/3 wait]: {}ms（等旧 name goodbye 传播）", GOODBYE_WAIT_MS);
            Thread.sleep(GOODBYE_WAIT_MS);
            log.info("[test-discovery] zeroconf reannounce[3/3 register]: {}（新 name 注册，listener 必然 resolve）",
                    fresh.getName());
            jmdns.registerService(fresh);
            serviceInfo = fresh;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[test-discovery] zeroconf reannounce 失败（忽略，下个周期重试）: {}", e.getMessage());
        }
    }

    /** 停止广播（幂等）：停周期任务 + 注销服务 + 关闭 jmdns。*/
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (jmdns != null) {
            try {
                jmdns.close();
            } catch (Exception e) {
                log.warn("[test-discovery] jmdns close 异常", e);
            }
            jmdns = null;
        }
        serviceInfo = null;
    }

    /**
     * 构造 ServiceInfo：每次用新 service name（{@code SERVICE_NAME-<seq>}）。
     * <p>jmdns 对"新 name 的服务"必然触发 listener {@code serviceResolved}（同 name 持续广播则不重复 resolve，
     * listener cache 命中）。故周期广播靠**换 name** 强制 listener 重新发现；TXT {@code sn} 不变 →
     * flow 派生的 uniqueId 不变 → 去重/进 pending 逻辑正确（与 name 无关）。
     */
    private ServiceInfo buildServiceInfo() {
        String name = SERVICE_NAME + "-" + nameSeq.getAndIncrement();
        Map<String, String> txt = new HashMap<String, String>();
        txt.put("model", ProbeHttpServer.MODEL);
        txt.put("sn", ProbeHttpServer.SN);
        txt.put("vendor", ProbeHttpServer.VENDOR);
        return ServiceInfo.create(SERVICE_TYPE, name, ProbeHttpServer.PORT, 0, 0, txt);
    }
}

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
 * <p><b>周期重新广播机制</b>：unregister + register（用全新 ServiceInfo）强制一次新的 announce 周期——
 * listener 侧触发 serviceResolved → 再次 startDiscoveryFlow（已添加设备 → 走 R5 去重路径）。jmdns 自身的 TTL
 * 重广播间隔过长（不可观察），故显式周期刷新。
 *
 * @author coffee
 */
public class ZeroconfServiceBroadcaster {

    private static final String SERVICE_TYPE = "_ecat-test._tcp.local.";
    private static final String SERVICE_NAME = "TestDiscovery-zeroconf001";
    /** 周期重新广播间隔（秒）——持续触发"已添加设备重复广播"，验证去重鲁棒性。*/
    static final long REANNOUNCE_INTERVAL_SEC = 60L;

    private final Log log = LogFactory.getLogger(getClass());
    private volatile boolean running = false;
    private JmDNS jmdns;
    private ServiceInfo serviceInfo;
    private ScheduledExecutorService scheduler;

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
     * 周期重新广播：unregister + register（全新 ServiceInfo）强制新 announce 周期。
     * listener 侧 serviceResolved → 再次 startDiscoveryFlow（已添加 → R5 去重）。
     */
    private void reannounce() {
        if (!running) {
            return;
        }
        try {
            ServiceInfo old = serviceInfo;
            ServiceInfo fresh = buildServiceInfo();
            jmdns.unregisterService(old);
            jmdns.registerService(fresh);
            serviceInfo = fresh;
            log.info("[test-discovery] zeroconf 周期重新广播（压测已添加设备重复广播的去重）: {}", SERVICE_NAME);
        } catch (Exception e) {
            log.warn("[test-discovery] zeroconf 周期重新广播失败（忽略，下个周期重试）: {}", e.getMessage());
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

    private ServiceInfo buildServiceInfo() {
        Map<String, String> txt = new HashMap<String, String>();
        txt.put("model", ProbeHttpServer.MODEL);
        txt.put("sn", ProbeHttpServer.SN);
        txt.put("vendor", ProbeHttpServer.VENDOR);
        return ServiceInfo.create(SERVICE_TYPE, SERVICE_NAME, ProbeHttpServer.PORT, 0, 0, txt);
    }
}

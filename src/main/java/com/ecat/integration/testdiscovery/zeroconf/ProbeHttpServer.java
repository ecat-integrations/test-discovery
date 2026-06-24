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

import com.ecat.integration.HttpServerIntegration.EasyHttpServer;

import java.util.HashMap;
import java.util.Map;

/**
 * zeroconf 闭环的"被发现的设备"侧——一个真实 HTTP server，用 {@code integration-httpserver} 起监听，
 * 对 {@code GET /identify} 回 {@code {model,sn,vendor}}。
 *
 * <p>这是<b>真实探活验证</b>的受探方：mDNS 广播的端口即本 server 监听端口；discovery 命中后 flow 的 probe 步
 * 用 {@link ProbeHttpClient} 真连 {@code http://<ip>:<PORT>/identify}，校验返回的 model/sn 与 TXT 声明一致——
 * 不一致/不可达则 ABORT，不入网伪装或离线设备。
 *
 * <p>常量（PORT/MODEL/SN/VENDOR）是广播方（{@link ZeroconfTestLoop}）与受探方（本类）的唯一真相源，
 * 双方引用同一组常量，保证"广播声明"与"探活应答"必然吻合。
 *
 * <p>线程模式：handler 经 {@link EasyHttpServer#blocking} 自动 dispatch 到 Worker Thread 并启用阻塞 I/O
 * （{@code sendJsonResponse} 用阻塞写入）。
 *
 * @author coffee
 */
public class ProbeHttpServer {

    /** 受探 HTTP server 监听端口（= mDNS 广播端口）。*/
    public static final int PORT = 18081;
    /** 设备型号（广播 TXT + 探活应答 共用）。*/
    public static final String MODEL = "TestDiscoveryDevice";
    /** 设备序列号（广播 TXT sn + 探活应答 + uniqueId 派生 共用）。*/
    public static final String SN = "zeroconf001";
    /** 厂商标识。*/
    public static final String VENDOR = "ECAT-Test";
    /** 探活端点路径。*/
    public static final String IDENTIFY_PATH = "/identify";

    private EasyHttpServer server;

    /**
     * 启动受探 server：监听 {@code 0.0.0.0:PORT}，注册 {@code GET /identify}。
     * <p>注意：{@link EasyHttpServer#registerUrl} 要求 undertow 非空，故必须 {@code start()} 后再 register。
     */
    public void start() {
        server = new EasyHttpServer("0.0.0.0", PORT);
        server.start();
        server.registerUrl(IDENTIFY_PATH, "GET", EasyHttpServer.blocking(exchange -> {
            Map<String, Object> body = new HashMap<String, Object>();
            body.put("model", MODEL);
            body.put("sn", SN);
            body.put("vendor", VENDOR);
            EasyHttpServer.sendJsonResponse(exchange, 200, body);
        }));
    }

    /** 停止受探 server（幂等）。*/
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}

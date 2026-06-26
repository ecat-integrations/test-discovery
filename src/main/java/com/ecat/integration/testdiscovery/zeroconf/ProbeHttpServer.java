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
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * zeroconf 闭环的"被发现的设备"侧——一个真实 HTTP server，用 {@code integration-httpserver} 起监听，
 * 对 {@code GET /identify} 回 {@code {model,sn,vendor}}。
 *
 * <p>这是<b>真实探活验证</b>的受探方：mDNS 广播的端口即本 server 监听端口；discovery 命中后 flow 的 probe 步
 * 用 {@link ProbeHttpClient} 真连 {@code http://<ip>:<PORT>/identify}，校验返回的 model/sn 与 TXT 声明一致——
 * 不一致/不可达则 ABORT，不入网伪装或离线设备。
 *
 * <p>另注册 <b>{@code GET /data}</b>（HTTP Basic Auth，账号 {@link #AUTH_ACCOUNT}/{@link #AUTH_PASSWORD}）——
 * 入网后的仿真设备（{@link TestDiscoverySimulatedDevice}）用 entry 里的凭证周期拉取，200 返回带轻微变化的
 * {temperature,humidity,rssi}（模拟实时传感器）；凭证缺失/不符回 401，设备据此置 OFFLINE。
 * 这构成"探活(/identify)无认证 + 拉数据(/data)需认证"的真实设备双端点示例。
 *
 * <p>常量（PORT/MODEL/SN/VENDOR）是广播方（{@link ZeroconfServiceBroadcaster}）与受探方（本类）的唯一真相源，
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
    /** 认证数据端点路径（入网后设备凭证拉取传感器数据）。*/
    public static final String DATA_PATH = "/data";
    /** /data 端点合法账号（向导凭证步提示用户输入；demo 用 admin）。*/
    public static final String AUTH_ACCOUNT = "admin";
    /** /data 端点合法密码（demo 用 123）。*/
    public static final String AUTH_PASSWORD = "123";

    private final Random rnd = new Random();
    private EasyHttpServer server;

    /**
     * 启动受探 server：监听 {@code 0.0.0.0:PORT}，注册两个端点：
     * <ul>
     *   <li>{@code GET /identify}（无认证）——探活，回 {model,sn,vendor}。</li>
     *   <li>{@code GET /data}（HTTP Basic Auth）——凭证校验通过回 {temperature,humidity,rssi}，否则 401。</li>
     * </ul>
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
        server.registerUrl(DATA_PATH, "GET", EasyHttpServer.blocking(this::handleDataRequest));
    }

    /**
     * 处理 {@code GET /data}：Basic Auth 校验 → 通过回传感器数据（带轻微变化），否则 401。
     * <p>严格模式：凭证缺失/不符/格式错一律 401，不静默放行。
     */
    private void handleDataRequest(EasyHttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!isBasicAuthValid(auth)) {
            Map<String, Object> err = new HashMap<String, Object>();
            err.put("error", "unauthorized");
            EasyHttpServer.sendJsonResponse(exchange, 401, err);
            return;
        }
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("temperature", 25f + (rnd.nextFloat() * 10f - 5f));   // 20~30°C
        body.put("humidity", 50f + (rnd.nextFloat() * 30f - 15f));     // 35~65%
        body.put("rssi", -55f + (rnd.nextFloat() * 20f - 10f));        // -65~-45 dBm
        EasyHttpServer.sendJsonResponse(exchange, 200, body);
    }

    /**
     * 校验 Basic Auth：解析 {@code Basic <base64>} → {@code account:password} → 比对常量。
     * <p>缺失前缀 / base64 损坏 / 缺冒号 / 值不符 → false（调用方回 401）。
     */
    private static boolean isBasicAuthValid(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }
        String decoded;
        try {
            byte[] bytes = Base64.getDecoder().decode(authHeader.substring("Basic ".length()).trim());
            decoded = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false; // base64 损坏 = 凭证非法
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String account = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        return AUTH_ACCOUNT.equals(account) && AUTH_PASSWORD.equals(password);
    }

    /** 停止受探 server（幂等）。*/
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}

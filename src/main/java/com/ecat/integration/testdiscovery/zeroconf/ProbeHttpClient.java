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

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * probe 步的探活客户端：真连 {@code http://ip:port/identify}，解析 JSON，校验 model/sn 与预期一致。
 *
 * <p>用途：discovery 命中后，flow 的 probe 步用预填的 ip/port + TXT 声明的 model/sn 调用
 * {@link #probe}——只信应答、不只信广播。任何失败（连接拒绝/超时/状态码非 200/JSON 缺字段/值不符）
 * 一律返回 {@code false}，由调用方按 ABORT 处理（严格模式：不兜底、不静默入网）。
 *
 * <p>纯 JDK {@link HttpURLConnection} + fastjson2，无外部依赖。
 *
 * @author coffee
 */
public final class ProbeHttpClient {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;

    private ProbeHttpClient() {
    }

    /**
     * 探活：GET {@code http://ip:port/identify}，校验应答 model/sn。
     *
     * @param ip            discovered IP（discovery 预填，已挑 IPv4）
     * @param port          discovered port（= {@link ProbeHttpServer#PORT}）
     * @param expectedModel 预期型号（TXT 声明）
     * @param expectedSn    预期序列号（TXT 声明）
     * @return true 仅当 HTTP 200 且应答 model/sn 与预期完全一致；否则 false（含任何异常）
     */
    public static boolean probe(String ip, int port, String expectedModel, String expectedSn) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + ProbeHttpServer.IDENTIFY_PATH);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                return false;
            }
            String body;
            try (InputStream is = conn.getInputStream()) {
                body = readAll(is);
            }
            JSONObject json = JSON.parseObject(body);
            return expectedModel.equals(json.getString("model"))
                    && expectedSn.equals(json.getString("sn"));
        } catch (Exception e) {
            // 不可达 / 超时 / 解析失败 → 探活失败（调用方 ABORT）
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}

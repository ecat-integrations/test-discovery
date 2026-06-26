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
import java.util.Base64;

/**
 * zeroconf 闭环的 HTTP 客户端：探活（{@link #probe}）+ 凭证拉数据（{@link #pullData}）。
 *
 * <p>{@link #probe}：discovery 命中后，flow 的 probe 步用预填的 ip/port + TXT 声明的 model/sn 调用，
 * 真连 {@code http://ip:port/identify}——只信应答、不只信广播。任何失败（连接拒绝/超时/状态码非 200/
 * JSON 缺字段/值不符）一律返回 {@code false}（严格模式：不兜底、不静默入网）。
 *
 * <p>{@link #pullData}：入网后的仿真设备用 entry 内的账号密码带 HTTP Basic Auth 真连
 * {@code http://ip:port/data}，拉取传感器数据（temperature/humidity/rssi）——构成"凭证访问设备拉数据"
 * 的真实示例。凭证无效(401)/不可达/解析失败 → 返回 null（设备置 OFFLINE，不静默兜底）。
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

    /**
     * 凭证拉数据：带 HTTP Basic Auth GET {@code http://ip:port/data}，解析 {temperature,humidity,rssi}。
     *
     * @param ip       设备地址（entry 内，zeroconf 来自 discovery、import-flow 为 127.0.0.1）
     * @param port     设备端口（= {@link ProbeHttpServer#PORT}）
     * @param account  访问账号（用户在凭证步输入 / import payload 携带）
     * @param password 访问密码
     * @return 解析后的传感器数据；HTTP 非 200（含 401 凭证无效）/ 不可达 / 解析失败 → null
     */
    public static SensorData pullData(String ip, int port, String account, String password) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + ProbeHttpServer.DATA_PATH);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            String basic = Base64.getEncoder().encodeToString(
                    (account + ":" + password).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + basic);
            int code = conn.getResponseCode();
            if (code != 200) {
                return null; // 401 凭证无效 / 其他非 200 → 拉取失败
            }
            String body;
            try (InputStream is = conn.getInputStream()) {
                body = readAll(is);
            }
            JSONObject json = JSON.parseObject(body);
            return new SensorData(
                    json.getFloatValue("temperature"),
                    json.getFloatValue("humidity"),
                    json.getFloatValue("rssi"));
        } catch (Exception e) {
            // 不可达 / 超时 / 解析失败 → 拉取失败（设备置 OFFLINE）
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** {@code /data} 应答解析出的传感器读数（final 字段，强类型承载）。*/
    public static final class SensorData {
        public final float temperature;
        public final float humidity;
        public final float rssi;

        public SensorData(float temperature, float humidity, float rssi) {
            this.temperature = temperature;
            this.humidity = humidity;
            this.rssi = rssi;
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

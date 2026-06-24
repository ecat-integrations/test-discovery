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

import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * zeroconf 闭环的<b>结果写入器</b>——把 E2E 结果（status/summary）落盘成固定格式文件。
 *
 * <p><b>单一职责</b>：结果文件 IO。不关心广播 / flow / 设备，也不决定 status/summary 的语义（那些由编排方给定）。
 *
 * <p>构造时固定 文件名 / 标题 / uniqueId（一次 E2E 内不变），仅 status/summary 每次写入时变化。
 *
 * @author coffee
 */
public class E2EResultWriter {

    private final String fileName;
    private final String title;
    private final String uniqueId;
    private final Log log = LogFactory.getLogger(getClass());

    public E2EResultWriter(String fileName, String title, String uniqueId) {
        this.fileName = fileName;
        this.title = title;
        this.uniqueId = uniqueId;
    }

    /**
     * 写一次结果（覆盖写）。
     *
     * @param status  SUCCESS / FAIL
     * @param summary 结果摘要
     */
    public void write(String status, String summary) {
        log.info("[test-discovery] zeroconf E2E 结果: status={}, summary={}", status, summary);
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new FileWriter(fileName));
            pw.println(title);
            pw.println("status: " + status);
            pw.println("uniqueId: " + uniqueId);
            pw.println("summary: " + summary);
            pw.println("timestamp_ms: " + System.currentTimeMillis());
            pw.println("=== done ===");
        } catch (Exception e) {
            log.error("[test-discovery] 写 zeroconf 结果文件失败", e);
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
    }
}

package com.ecat.integration.testdiscovery.zeroconf;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link E2EResultWriter} 单测——验证结果文件格式（title/status/uniqueId/summary/done 落盘正确）。
 * <p>写临时文件后读回校验。
 */
public class E2EResultWriterTest {

    @Test
    public void testWrite_ProdusCorrectFormat() throws Exception {
        File tmp = File.createTempFile("e2e-result-test", ".txt");
        E2EResultWriter writer = new E2EResultWriter(tmp.getAbsolutePath(), "test title", "uid-123");

        writer.write("SUCCESS", "闭环通过");

        String content = new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
        assertTrue("应含 title", content.contains("test title"));
        assertTrue("应含 status", content.contains("status: SUCCESS"));
        assertTrue("应含 uniqueId", content.contains("uniqueId: uid-123"));
        assertTrue("应含 summary", content.contains("summary: 闭环通过"));
        assertTrue("应以 done 结尾", content.trim().endsWith("=== done ==="));
    }

    @Test
    public void testWrite_OverwritesPrevious() throws Exception {
        File tmp = File.createTempFile("e2e-result-test2", ".txt");
        E2EResultWriter writer = new E2EResultWriter(tmp.getAbsolutePath(), "t", "u");
        writer.write("FAIL", "第一次");
        writer.write("SUCCESS", "第二次");

        String content = new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
        assertEquals("覆盖写后应只剩第二次的 status", 1, countOccurrences(content, "status: "));
        assertTrue("应为第二次的 SUCCESS", content.contains("status: SUCCESS"));
        assertTrue("应为第二次的 summary", content.contains("summary: 第二次"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

package org.aiknowledgebase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 前端引用卡片构建验证：确认 index.html 的来源渲染包含
 * 版本号 / 字符偏移 / 相似度得分字段。
 */
class FrontendSourceCardTest {

    @Test
    void indexHtmlRendersVersionAwareSourceCard() throws Exception {
        String html = Files.readString(
                Path.of("src/main/resources/static/index.html"));

        assertTrue(html.contains("source.versionNo"), "引用卡片应渲染版本号");
        assertTrue(html.contains("source.charOffset"), "引用卡片应渲染字符偏移");
        assertTrue(html.contains("source.similarityScore"), "引用卡片应渲染相似度得分");
        assertTrue(html.contains("source-meta"), "引用卡片应包含元数据样式节点");
    }
}

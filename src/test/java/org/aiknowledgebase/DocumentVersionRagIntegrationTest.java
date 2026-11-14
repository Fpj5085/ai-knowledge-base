package org.aiknowledgebase;

import org.aiknowledgebase.dto.ChunkSearchResult;
import org.aiknowledgebase.dto.RagAnswerResponse;
import org.aiknowledgebase.dto.RagSource;
import org.aiknowledgebase.dto.DocumentUploadResponse;
import org.aiknowledgebase.entity.DocumentVersion;
import org.aiknowledgebase.entity.KnowledgeBase;
import org.aiknowledgebase.repository.DocumentVersionRepository;
import org.aiknowledgebase.repository.KnowledgeBaseRepository;
import org.aiknowledgebase.service.DocumentService;
import org.aiknowledgebase.service.EmbeddingService;
import org.aiknowledgebase.service.LlmService;
import org.aiknowledgebase.service.RagService;
import org.aiknowledgebase.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * B2 集成测试：文档版本管理 + 来源引用追踪。
 * 使用 mock embedding（确定性向量）与 mock LLM，不产生任何真实模型调用。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentVersionRagIntegrationTest {

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentVersionRepository documentVersionRepository;
    @Autowired
    private VectorStoreService vectorStoreService;
    @Autowired
    private RagService ragService;

    @MockitoBean
    private EmbeddingService embeddingService;
    @MockitoBean
    private LlmService llmService;

    private static Long kbId;
    private static Long docId;
    private static Long v1VersionId;

    private static final String V1_TEXT = "人工智能知识库演示文档第一版。本段介绍机器学习的基础概念与监督学习流程。";
    private static final String V2_TEXT = "人工智能知识库演示文档第二版。本段介绍量子计算与量子机器学习的最新进展。";

    @MockitoBean
    private org.aiknowledgebase.service.WebSearchService webSearchService;

    @BeforeEach
    void setupMocks() {
        EmbeddingService embeddingServiceMock = embeddingService;
        LlmService llmServiceMock = llmService;
        // 确定性 embedding：同文本同向量，便于 pgvector 检索断言
        lenient().when(embeddingServiceMock.embed(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0, String.class);
            double[] vector = new double[EmbeddingService.VECTOR_DIM];
            if (text == null || text.isBlank()) {
                return vector;
            }
            Random random = new Random(text.hashCode() * 7919L);
            for (int i = 0; i < vector.length; i++) {
                vector[i] = random.nextDouble();
            }
            return vector;
        });
        lenient().when(embeddingServiceMock.toVectorLiteral(any()))
                .thenAnswer(inv -> {
                    double[] vector = inv.getArgument(0);
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < vector.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(vector[i]);
                    }
                    return sb.append("]").toString();
                });

        // LLM 固定回答 YES：既通过相关性判断，也作为最终回答断言值
        lenient().when(llmServiceMock.chat(anyString())).thenReturn("YES");
    }

    @Test
    @Order(1)
    void step1_uploadCreatesV1() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName("b2-it-" + UUID.randomUUID());
        kb.setDescription("B2 integration test");
        kbId = knowledgeBaseRepository.save(kb).getId();

        DocumentUploadResponse resp = upload(V1_TEXT);
        docId = resp.getDocumentId();
        assertEquals(1, resp.getVersionNo(), "首次上传应生成 v1");

        List<DocumentVersion> versions = documentVersionRepository
                .findByDocumentIdOrderByVersionNoAsc(docId);
        assertEquals(1, versions.size());
        v1VersionId = versions.get(0).getId();
        assertNotNull(versions.get(0).getContentHash());
        assertEquals(64, versions.get(0).getContentHash().length());
    }

    @Test
    @Order(2)
    void step2_sameContentDoesNotCreateNewVersion() {
        DocumentUploadResponse resp = upload(V1_TEXT);
        assertEquals(docId, resp.getDocumentId());
        assertEquals(1, resp.getVersionNo(), "内容相同的重传不应产生新版本");
        assertEquals(1, documentVersionRepository.findByDocumentIdOrderByVersionNoAsc(docId).size());
    }

    @Test
    @Order(3)
    void step3_modifiedContentCreatesV2() {
        DocumentUploadResponse resp = upload(V2_TEXT);
        assertEquals(docId, resp.getDocumentId());
        assertEquals(2, resp.getVersionNo(), "内容变化应生成 v2");
        assertEquals(2, documentVersionRepository.findByDocumentIdOrderByVersionNoAsc(docId).size());

        vectorStoreService.buildEmbeddings(kbId);
    }

    @Test
    @Order(4)
    void step4_searchDefaultsToLatestVersion() {
        List<ChunkSearchResult> results = vectorStoreService.searchSimilarChunks(
                kbId, "量子计算与量子机器学习的最新进展", 5, null);

        assertFalse(results.isEmpty(), "默认检索应命中最新版本切片");
        for (ChunkSearchResult result : results) {
            if (docId.equals(result.getDocumentId())) {
                assertEquals(2, result.getVersionNo(), "默认检索只应返回最新版本 v2");
                assertNotNull(result.getCharOffset(), "引用应携带字符偏移");
            }
        }
        assertTrue(results.stream().anyMatch(r ->
                docId.equals(r.getDocumentId()) && r.getContent().contains("量子计算")),
                "检索结果应包含 v2 片段原文");
    }

    @Test
    @Order(5)
    void step5_searchSpecificVersion() {
        List<ChunkSearchResult> results = vectorStoreService.searchSimilarChunks(
                kbId, "机器学习的基础概念", 5, v1VersionId);

        assertFalse(results.isEmpty(), "指定 v1 版本检索应命中 v1 切片");
        for (ChunkSearchResult result : results) {
            if (docId.equals(result.getDocumentId())) {
                assertEquals(1, result.getVersionNo(), "指定版本检索应只返回 v1");
                assertTrue(result.getContent().contains("机器学习"),
                        "指定版本检索应返回 v1 片段原文");
            }
        }
    }

    @Test
    @Order(6)
    void step6_ragAnswerCarriesTraceableSources() {
        RagAnswerResponse response = ragService.ask(
                99901L, kbId, "文档里介绍了什么？", 5, null, null, false, null);

        assertEquals("YES", response.getAnswer());
        List<RagSource> sources = response.getSources();
        assertFalse(sources.isEmpty(), "RAG 回答应携带引用来源");
        for (RagSource source : sources) {
            if (docId.equals(source.getDocumentId())) {
                assertEquals(2, source.getVersionNo(), "默认问答引用应指向最新版本 v2");
                assertNotNull(source.getCharOffset(), "引用应携带字符偏移");
                assertNotNull(source.getSimilarityScore(), "引用应携带相似度得分");
                assertNotNull(source.getContent(), "引用应携带片段原文");
            }
        }
    }

    @Test
    @Order(7)
    void step7_versionPreviewEndpointData() {
        Map<String, Object> preview = documentService.previewVersion(docId, 2);
        assertEquals(2, preview.get("versionNo"));
        assertTrue(((String) preview.get("fullText")).contains("量子计算"));

        List<DocumentVersion> versions = documentService.listVersions(docId);
        assertEquals(2, versions.size());
    }

    private DocumentUploadResponse upload(String content) {
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.md", "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
        return documentService.uploadDocument(kbId, file);
    }
}

package org.aiknowledgebase.service;

import org.aiknowledgebase.dto.DocumentUploadResponse;
import org.aiknowledgebase.entity.DocumentInfo;
import org.aiknowledgebase.entity.DocumentVersion;
import org.aiknowledgebase.entity.KnowledgeBase;
import org.aiknowledgebase.entity.TextChunk;
import org.aiknowledgebase.repository.DocumentInfoRepository;
import org.aiknowledgebase.repository.DocumentVersionRepository;
import org.aiknowledgebase.repository.KnowledgeBaseRepository;
import org.aiknowledgebase.repository.TextChunkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 80;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentInfoRepository documentInfoRepository;
    private final TextChunkRepository textChunkRepository;
    private final DocumentVersionRepository documentVersionRepository;

    public DocumentService(KnowledgeBaseRepository knowledgeBaseRepository,
                           DocumentInfoRepository documentInfoRepository,
                           TextChunkRepository textChunkRepository,
                           DocumentVersionRepository documentVersionRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentInfoRepository = documentInfoRepository;
        this.textChunkRepository = textChunkRepository;
        this.documentVersionRepository = documentVersionRepository;
    }

    /**
     * 上传并解析文档。同一知识库内同名文档按 content_hash 判定：
     * 内容相同不重复入库；内容变化生成新版本并重建该版本的切片。
     */
    @Transactional
    public DocumentUploadResponse uploadDocument(Long knowledgeBaseId, MultipartFile file) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在，ID = " + knowledgeBaseId));

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String fileName = file.getOriginalFilename();
        String fileType = getFileType(fileName);

        if (!"txt".equalsIgnoreCase(fileType) && !"md".equalsIgnoreCase(fileType)) {
            throw new IllegalArgumentException("当前版本仅支持 txt 和 md 文件");
        }

        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            String normalizedContent = content
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .trim();

            String contentHash = sha256(normalizedContent);
            List<ChunkData> chunks = splitText(normalizedContent, CHUNK_SIZE, CHUNK_OVERLAP);

            DocumentInfo documentInfo = documentInfoRepository
                    .findFirstByKnowledgeBaseIdAndFileNameIgnoreCaseOrderByUpdateTimeDesc(
                            knowledgeBase.getId(), fileName)
                    .orElseGet(() -> {
                        DocumentInfo created = new DocumentInfo();
                        created.setKnowledgeBaseId(knowledgeBase.getId());
                        created.setFileName(fileName);
                        return created;
                    });

            documentInfo.setFileType(fileType);
            documentInfo.setFileSize(file.getSize());
            documentInfo.setChunkCount(chunks.size());
            documentInfo.setStatus("PARSED");

            DocumentVersion latestVersion = documentInfo.getId() == null
                    ? null
                    : documentVersionRepository
                        .findTopByDocumentIdOrderByVersionNoDesc(documentInfo.getId())
                        .orElse(null);

            if (latestVersion != null && contentHash.equals(latestVersion.getContentHash())) {
                documentInfo.setUpdateTime(LocalDateTime.now());
                documentInfoRepository.save(documentInfo);
                return new DocumentUploadResponse(
                        documentInfo.getId(),
                        documentInfo.getFileName(),
                        documentInfo.getChunkCount(),
                        documentInfo.getStatus(),
                        latestVersion.getVersionNo());
            }

            int newVersionNo = latestVersion == null ? 1 : latestVersion.getVersionNo() + 1;

            DocumentInfo savedDocument = documentInfoRepository.save(documentInfo);

            DocumentVersion documentVersion = new DocumentVersion();
            documentVersion.setDocumentId(savedDocument.getId());
            documentVersion.setVersionNo(newVersionNo);
            documentVersion.setFileName(fileName);
            documentVersion.setFileSize(file.getSize());
            documentVersion.setFileType(fileType);
            documentVersion.setContentHash(contentHash);
            documentVersion.setStorageKey("kb/" + savedDocument.getId() + "/v" + newVersionNo);
            documentVersion.setChangeNote(latestVersion == null ? "首次上传" : "内容变更，重新入库并向量化");

            DocumentVersion savedVersion;
            try {
                savedVersion = documentVersionRepository.save(documentVersion);
                documentVersionRepository.flush();
            } catch (DataIntegrityViolationException e) {
                int retryVersionNo = documentVersionRepository
                        .findTopByDocumentIdOrderByVersionNoDesc(savedDocument.getId())
                        .map(v -> v.getVersionNo() + 1)
                        .orElse(newVersionNo);
                documentVersion.setVersionNo(retryVersionNo);
                savedVersion = documentVersionRepository.save(documentVersion);
            }

            List<TextChunk> textChunks = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkData chunkData = chunks.get(i);

                TextChunk textChunk = new TextChunk();
                textChunk.setKnowledgeBaseId(knowledgeBase.getId());
                textChunk.setDocumentId(savedDocument.getId());
                textChunk.setDocumentVersionId(savedVersion.getId());
                textChunk.setChunkIndex(i);
                textChunk.setContent(chunkData.content());
                textChunk.setCharCount(chunkData.content().length());
                textChunk.setCharOffset(chunkData.startOffset());

                textChunks.add(textChunk);
            }

            textChunkRepository.saveAll(textChunks);

            return new DocumentUploadResponse(
                    savedDocument.getId(),
                    savedDocument.getFileName(),
                    savedDocument.getChunkCount(),
                    savedDocument.getStatus(),
                    savedVersion.getVersionNo());
        } catch (Exception e) {
            throw new RuntimeException("文档上传或解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 根据知识库 ID 查询文档列表
     */
    public List<DocumentInfo> listDocuments(Long knowledgeBaseId) {
        return documentInfoRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    /**
     * 根据文档 ID 查询切片列表
     */
    public List<TextChunk> listChunks(Long documentId) {
        return textChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
    }

    /**
     * 查询文档全部版本
     */
    public List<DocumentVersion> listVersions(Long documentId) {
        return documentVersionRepository.findByDocumentIdOrderByVersionNoAsc(documentId);
    }

    /**
     * 预览指定版本：版本元数据 + 该版本切片全文
     */
    public Map<String, Object> previewVersion(Long documentId, Integer versionNo) {
        DocumentVersion version = documentVersionRepository
                .findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "文档版本不存在，documentId = " + documentId + "，versionNo = " + versionNo));

        List<TextChunk> versionChunks = textChunkRepository
                .findByDocumentIdAndDocumentVersionIdOrderByChunkIndexAsc(documentId, version.getId());

        StringBuilder fullText = new StringBuilder();
        for (TextChunk chunk : versionChunks) {
            fullText.append(chunk.getContent()).append("\n");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", version.getDocumentId());
        result.put("versionNo", version.getVersionNo());
        result.put("fileName", version.getFileName());
        result.put("fileSize", version.getFileSize());
        result.put("fileType", version.getFileType());
        result.put("contentHash", version.getContentHash());
        result.put("changeNote", version.getChangeNote());
        result.put("createTime", version.getCreateTime());
        result.put("chunkCount", versionChunks.size());
        result.put("fullText", fullText.toString());
        return result;
    }

    private List<ChunkData> splitText(String text, int chunkSize, int overlap) {
        List<ChunkData> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        int start = 0;
        int length = text.length();

        while (start < length) {
            int end = Math.min(start + chunkSize, length);
            String rawChunk = text.substring(start, end);
            String chunk = rawChunk.trim();

            if (!chunk.isEmpty()) {
                int leading = rawChunk.indexOf(chunk.charAt(0));
                chunks.add(new ChunkData(chunk, start + Math.max(leading, 0)));
            }

            if (end >= length) {
                break;
            }

            start = end - overlap;
            if (start < 0) {
                start = 0;
            }
        }

        return chunks;
    }

    private record ChunkData(String content, int startOffset) {
    }

    private String getFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }
}

package org.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DocumentUploadResponse {

    private Long documentId;

    private String fileName;

    private Integer chunkCount;

    private String status;

    /**
     * 本次上传对应的文档版本号；内容未变化的重传返回当前最新版本号
     */
    private Integer versionNo;
}

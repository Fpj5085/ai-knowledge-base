package org.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChunkSearchResult {

    /**
     * 文本切片 ID
     */
    private Long chunkId;

    /**
     * 文档 ID
     */
    private Long documentId;

    /**
     * 当前切片在文档中的序号
     */
    private Integer chunkIndex;

    /**
     * 切片内容
     */
    private String content;

    /**
     * 向量距离，数值越小表示越相似
     */
    private Double distance;

    /**
     * 命中切片所属文档版本号；无版本数据（旧数据/上传文件切片）时为 null
     */
    private Integer versionNo;

    /**
     * 切片在归一化全文中的起始字符偏移
     */
    private Integer charOffset;
}

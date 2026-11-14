package org.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RagSource {

    /**
     * 文本切片 ID
     */
    private Long chunkId;

    /**
     * 文档 ID
     */
    private Long documentId;

    /**
     * 切片序号
     */
    private Integer chunkIndex;

    /**
     * 检索距离，越小越相似
     */
    private Double distance;

    /**
     * 被检索到的文本内容
     */
    private String content;

    /**
     * 引用所属文档版本号
     */
    private Integer versionNo;

    /**
     * 引用片段在归一化全文中的起始字符偏移
     */
    private Integer charOffset;

    /**
     * 相似度得分 = 1 - 向量距离，越大越相似
     */
    private Double similarityScore;
}

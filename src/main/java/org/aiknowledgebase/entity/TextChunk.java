package org.aiknowledgebase.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "kb_text_chunk")
public class TextChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属知识库 ID
     */
    @Column(nullable = false)
    private Long knowledgeBaseId;

    /**
     * 所属文档 ID
     */
    @Column(nullable = false)
    private Long documentId;

    /**
     * 所属文档版本 ID（kb_document_version.id），历史数据允许为空
     */
    @Column(name = "document_version_id")
    private Long documentVersionId;

    /**
     * 当前切片序号，从 0 开始
     */
    @Column(nullable = false)
    private Integer chunkIndex;

    /**
     * 切片文本内容
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 文本长度，先按字符数统计
     */
    private Integer charCount;

    /**
     * 切片在归一化全文中的起始字符偏移
     */
    @Column(name = "char_offset")
    private Integer charOffset;

    /**
     * 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
    }
}

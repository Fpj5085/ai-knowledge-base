package org.aiknowledgebase.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "kb_document_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_doc_version", columnNames = {"document_id", "version_no"}))
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属文档 ID（kb_document_info.id）
     */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /**
     * 版本号，从 1 开始，同一文档内递增
     */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_type", length = 50)
    private String fileType;

    /**
     * 归一化全文的 SHA-256，64 位十六进制
     */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "change_note", length = 500)
    private String changeNote;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
    }
}

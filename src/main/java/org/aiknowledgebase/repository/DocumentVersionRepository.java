package org.aiknowledgebase.repository;

import org.aiknowledgebase.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);

    List<DocumentVersion> findByDocumentIdOrderByVersionNoAsc(Long documentId);

    Optional<DocumentVersion> findByDocumentIdAndVersionNo(Long documentId, Integer versionNo);
}

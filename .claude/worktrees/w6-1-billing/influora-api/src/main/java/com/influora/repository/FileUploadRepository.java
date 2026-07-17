package com.influora.repository;

import com.influora.domain.entity.FileUpload;
import com.influora.domain.enums.FileOwnerType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileUploadRepository extends JpaRepository<FileUpload, String> {

    List<FileUpload> findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(String ownerId, FileOwnerType ownerType);
}

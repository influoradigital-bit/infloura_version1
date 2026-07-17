package com.influora.repository;

import com.influora.domain.entity.HsnSacCode;
import com.influora.domain.enums.HsnSacAppliesTo;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HsnSacCodeRepository extends JpaRepository<HsnSacCode, String> {

    Optional<HsnSacCode> findByAppliesTo(HsnSacAppliesTo appliesTo);
}

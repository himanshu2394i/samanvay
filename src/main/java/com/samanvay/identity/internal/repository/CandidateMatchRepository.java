package com.samanvay.identity.internal.repository;

import com.samanvay.identity.internal.domain.CandidateMatchEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateMatchRepository extends JpaRepository<CandidateMatchEntity, UUID> {}

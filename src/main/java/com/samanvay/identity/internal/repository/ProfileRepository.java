package com.samanvay.identity.internal.repository;

import com.samanvay.identity.internal.domain.ProfileEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<ProfileEntity, UUID> {}

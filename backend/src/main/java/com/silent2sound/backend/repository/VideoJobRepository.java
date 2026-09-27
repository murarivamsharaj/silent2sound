package com.silent2sound.backend.repository;

import com.silent2sound.backend.model.VideoJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface VideoJobRepository extends JpaRepository<VideoJob, UUID> {
}

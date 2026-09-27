package com.silent2sound.backend.service;

import com.silent2sound.backend.model.JobStatus;
import com.silent2sound.backend.model.VideoJob;
import com.silent2sound.backend.repository.VideoJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoUploadService {

    private final VideoJobRepository videoJobRepository;
    private final VideoProcessingPipelineService videoProcessingPipelineService;

    @Value("${app.storage.upload-dir}")
    private String uploadDir;

    @PostConstruct
    void init() {
        try {
            Path dir = Paths.get(uploadDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("Created upload directory: {}", dir.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create upload directory: " + uploadDir, e);
        }
    }

    public VideoJob storeAndQueue(MultipartFile file, String prompt) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty or missing.");
        }

        // 1. Persist the job first so Hibernate generates the id. storagePath is
        // NOT NULL, so a placeholder is stored now and replaced once the file lands.
        VideoJob job = VideoJob.builder()
                .originalFilename(StringUtils.cleanPath(
                        file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.mp4"))
                .storagePath("")
                .status(JobStatus.PENDING)
                .promptOverride(prompt)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        job = videoJobRepository.save(job);

        // 2. ...then store the bytes under the generated id as the filename.
        Path destination = Paths.get(uploadDir, job.getId() + ".mp4");
        try (var in = file.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // The row exists but the asset doesn't; mark the job failed.
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("File storage failed: " + e.getMessage());
            job.setUpdatedAt(LocalDateTime.now());
            videoJobRepository.save(job);
            throw new UncheckedIOException("Failed to store uploaded file: " + file.getOriginalFilename(), e);
        }

        job.setStoragePath(destination.toString());
        job.setUpdatedAt(LocalDateTime.now());
        job = videoJobRepository.save(job);

        // Kick off the (stub) async pipeline now that the asset is on disk.
        videoProcessingPipelineService.processVideoAsync(job.getId());

        return job;
    }
}

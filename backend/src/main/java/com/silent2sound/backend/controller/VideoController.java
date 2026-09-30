package com.silent2sound.backend.controller;

import com.silent2sound.backend.dto.VideoJobStatusResponse;
import com.silent2sound.backend.model.JobStatus;
import com.silent2sound.backend.model.VideoJob;
import com.silent2sound.backend.repository.VideoJobRepository;
import com.silent2sound.backend.service.VideoUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
)
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final VideoUploadService videoUploadService;
    private final VideoJobRepository videoJobRepository;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "prompt", required = false) String prompt) {

        VideoJob job = videoUploadService.storeAndQueue(file, prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getId().toString());
        body.put("status", job.getStatus().name());
        body.put("message", "Video uploaded successfully and queued for processing.");

        log.info("Uploaded video job {} queued (original file: {})", job.getId(), job.getOriginalFilename());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<VideoJobStatusResponse> getStatus(@PathVariable UUID jobId) {
        return videoJobRepository.findById(jobId)
                .map(VideoJobStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Serves the processed video with byte-range support so the browser
     * player can seek. Streams only COMPLETED jobs whose output file exists.
     */
    @GetMapping("/download/{jobId}")
    public ResponseEntity<Resource> download(@PathVariable UUID jobId) {
        VideoJob job = videoJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.COMPLETED) {
            return ResponseEntity.notFound().build();
        }

        Path filePath = Paths.get(job.getOutputPath());
        if (!Files.isRegularFile(filePath)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource;
        try {
            resource = new UrlResource(filePath.toUri());
        } catch (MalformedURLException e) {
            log.error("Invalid output path for job {}: {}", jobId, job.getOutputPath());
            return ResponseEntity.notFound().build();
        }

        String filename = sanitizeFilename(job.getOriginalFilename());
        long length = safeLength(filePath);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .eTag("\"" + jobId + "-" + length + "\"")
                .body(resource);
    }

    private static String sanitizeFilename(String original) {
        String base = (original == null || original.isBlank()) ? "video.mp4" : original;
        String cleaned = base.replaceAll("[\\r\\n\\\"\\\\]", "_");
        return cleaned.endsWith(".mp4") ? cleaned : cleaned + ".mp4";
    }

    private static long safeLength(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
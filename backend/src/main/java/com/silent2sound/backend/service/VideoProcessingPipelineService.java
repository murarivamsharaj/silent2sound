package com.silent2sound.backend.service;

import com.silent2sound.backend.model.JobStatus;
import com.silent2sound.backend.model.VideoJob;
import com.silent2sound.backend.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Real processing pipeline driving FFmpeg through {@link FfmpegService}.
 * Each status update is saved through the repository (its own transaction),
 * so the status endpoint observes live transitions while this method runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoProcessingPipelineService {

    private static final int FRAME_INTERVAL_SECONDS = 2;
    private static final double FALLBACK_DURATION_SECONDS = 10.0;

    private final VideoJobRepository videoJobRepository;
    private final FfmpegService ffmpegService;
    private final SceneAnalysisService sceneAnalysisService;
    private final AudioGenerationService audioGenerationService;

    @Value("${app.storage.upload-dir}")
    private String uploadDir;

    @Async
    public void processVideoAsync(UUID jobId) {
        try {
            VideoJob job = requireJob(jobId);
            Path videoPath = Paths.get(job.getStoragePath());

            // 1. ANALYZING — extract sample frames for scene understanding.
            updateStatus(jobId, JobStatus.ANALYZING);
            Path framesDir = Paths.get(uploadDir, "frames_" + jobId);
            List<Path> frames = ffmpegService.extractSampleFrames(
                    videoPath, framesDir, FRAME_INTERVAL_SECONDS);
            log.info("Job {}: extracted {} sample frames", jobId, frames.size());

            job = requireJob(jobId);
            String soundPrompt = sceneAnalysisService.analyzeFramesAndGeneratePrompt(
                    frames, job.getPromptOverride());
            job.setSceneDescription(soundPrompt);
            job.setUpdatedAt(LocalDateTime.now());
            videoJobRepository.save(job);
            log.info("Job {}: scene prompt: {}", jobId, soundPrompt);

            // 2. GENERATING_AUDIO — generate the audio track from the scene
            //    prompt (mock tone locally, ElevenLabs when configured).
            updateStatus(jobId, JobStatus.GENERATING_AUDIO);
            double durationSeconds = ffmpegService.probeDuration(videoPath)
                    .map(d -> d.toMillis() / 1000.0)
                    .orElse(FALLBACK_DURATION_SECONDS);
            Path audioPath = Paths.get(uploadDir, "audio_" + jobId + ".m4a");
            audioGenerationService.generateAudioTrack(
                    soundPrompt, Math.max(1.0, durationSeconds), audioPath);

            // 3. MERGING — mux original video with the synthetic audio.
            updateStatus(jobId, JobStatus.MERGING);
            Path outputPath = Paths.get(uploadDir, "output_" + jobId + ".mp4");
            ffmpegService.muxVideoAndAudio(videoPath, audioPath, outputPath);

            // 4. COMPLETED — publish the muxed file path.
            job = requireJob(jobId);
            job.setStatus(JobStatus.COMPLETED);
            job.setOutputPath(outputPath.toString());
            job.setUpdatedAt(LocalDateTime.now());
            videoJobRepository.save(job);
            log.info("Job {} -> COMPLETED ({})", jobId, outputPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(jobId, "Processing was interrupted.");
        } catch (Exception e) {
            log.error("Processing pipeline failed for job {}", jobId, e);
            markFailed(jobId, e.getMessage() != null ? e.getMessage() : "Unexpected processing error.");
        }
    }

    private void updateStatus(UUID jobId, JobStatus status) {
        VideoJob job = requireJob(jobId);
        job.setStatus(status);
        job.setUpdatedAt(LocalDateTime.now());
        videoJobRepository.save(job);
        log.info("Job {} -> {}", jobId, status);
    }

    private void markFailed(UUID jobId, String message) {
        videoJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(message);
            job.setUpdatedAt(LocalDateTime.now());
            videoJobRepository.save(job);
            log.info("Job {} -> FAILED ({})", jobId, message);
        });
    }

    private VideoJob requireJob(UUID jobId) {
        return videoJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("VideoJob not found: " + jobId));
    }
}

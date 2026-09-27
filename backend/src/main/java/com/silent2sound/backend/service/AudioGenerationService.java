package com.silent2sound.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Generates the audio track for a job. In mock mode it delegates to FFmpeg's
 * synthetic tone so local development needs no paid API keys; with the
 * ElevenLabs provider it calls the Sound Effects API and streams the binary
 * result to disk.
 */
@Service
@Slf4j
public class AudioGenerationService {

    private final FfmpegService ffmpegService;

    public AudioGenerationService(FfmpegService ffmpegService) {
        this.ffmpegService = ffmpegService;
    }

    @Value("${app.ai.provider}")
    private String provider;

    @Value("${app.ai.elevenlabs.api-key}")
    private String elevenLabsApiKey;

    public void generateAudioTrack(String soundPrompt, double durationSeconds, Path outputWavPath)
            throws IOException, InterruptedException {

        if (!"elevenlabs".equalsIgnoreCase(provider) || isBlank(elevenLabsApiKey)) {
            log.info("Generating audio via FFmpeg synthetic tone (provider={}, keyPresent={})",
                    provider, !isBlank(elevenLabsApiKey));
            ffmpegService.generateSyntheticToneAudio(outputWavPath, durationSeconds);
            return;
        }

        log.info("Generating audio via ElevenLabs sound effects (prompt='{}', duration={}s)",
                soundPrompt, durationSeconds);

        String payload = """
                {
                  "text": "%s",
                  "duration_seconds": %s
                }
                """.formatted(escapeJson(soundPrompt), formatDuration(durationSeconds));

        byte[] audio;
        try {
            WebClient webClient = WebClient.builder()
                    .baseUrl("https://api.elevenlabs.io")
                    .build();
            audio = webClient.post()
                    .uri("/v1/sound-generation")
                    .header("xi-api-key", elevenLabsApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(120));
        } catch (Exception e) {
            throw new IOException("ElevenLabs sound generation failed: " + e.getMessage(), e);
        }

        if (audio == null || audio.length == 0) {
            throw new IOException("ElevenLabs returned an empty audio payload.");
        }

        Files.createDirectories(outputWavPath.toAbsolutePath().getParent());
        Path tmp = outputWavPath.resolveSibling(outputWavPath.getFileName() + ".tmp");
        Files.write(tmp, audio);
        Files.move(tmp, outputWavPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("ElevenLabs audio written to {} ({} bytes)", outputWavPath, audio.length);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String formatDuration(double durationSeconds) {
        // Avoid scientific notation / locale digits in the JSON payload.
        return String.format(java.util.Locale.ROOT, "%.2f", durationSeconds);
    }
}

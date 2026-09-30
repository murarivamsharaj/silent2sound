package com.silent2sound.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.MultipartBodyBuilder;

/**
 * Optional Wav2Lip pass. Disabled (or without a Replicate token) it simply
 * copies the input video through so the pipeline stays fully local.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LipSyncService {

    private static final String WAV2LIP_MODEL_VERSION =
            "https://replicate.com/api/cog/wav2lip/v1";
    private static final long POLL_INTERVAL_MS = 2000;
    private static final long POLL_TIMEOUT_MS = 300_000;

    private final FfmpegService ffmpegService;

    @Value("${app.features.enable-lipsync}")
    private boolean lipsyncEnabled;

    @Value("${app.ai.replicate.api-token}")
    private String replicateApiToken;

    public void syncLipsIfEnabled(Path inputVideoPath, Path speechAudioPath, Path outputVideoPath)
            throws IOException, InterruptedException {

        if (!lipsyncEnabled || isBlank(replicateApiToken)) {
            log.info("Lip-sync disabled or no Replicate token; passing video through unchanged "
                    + "(enabled={}, tokenPresent={})", lipsyncEnabled, !isBlank(replicateApiToken));
            Files.createDirectories(outputVideoPath.toAbsolutePath().getParent());
            Files.copy(inputVideoPath, outputVideoPath, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        log.info("Lip-sync via Replicate Wav2Lip for {}", inputVideoPath.getFileName());
        Files.createDirectories(outputVideoPath.toAbsolutePath().getParent());

        try {
            String videoUri = uploadToReplicateFiles(inputVideoPath);
            String audioUri = uploadToReplicateFiles(speechAudioPath);

            JsonMapper mapper = JsonMapper.builder().build();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("version", WAV2LIP_MODEL_VERSION);
            payload.set("input", mapper.createObjectNode()
                    .put("video", videoUri)
                    .put("audio", audioUri));

            WebClient webClient = WebClient.builder()
                    .baseUrl("https://api.replicate.com")
                    .defaultHeader("Authorization", "Bearer " + replicateApiToken)
                    .build();

            JsonNode prediction = webClient.post()
                    .uri("/v1/predictions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload.toString())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));

            String predictionId = prediction == null ? null : prediction.path("id").asText(null);
            if (predictionId == null) {
                throw new IOException("Replicate did not return a prediction id.");
            }
            log.info("Replicate prediction {} created", predictionId);

            String outputUrl = pollUntilSucceeded(webClient, predictionId);
            downloadBinary(webClient, outputUrl, outputVideoPath);
            log.info("Lip-synced video saved to {}", outputVideoPath);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Replicate lip-sync failed: " + e.getMessage(), e);
        }
    }

    /**
     * Uploads an asset through Replicate's files API and returns the URL that
     * predictions can reference as an input value.
     */
    private String uploadToReplicateFiles(Path file) throws IOException {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.replicate.com")
                .defaultHeader("Authorization", "Bearer " + replicateApiToken)
                .build();

        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("content", new FileSystemResource(file.toAbsolutePath()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM);

        try {
            JsonNode uploaded = webClient.post()
                    .uri("/v1/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(parts.build()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(120));

            String url = uploaded == null ? null : uploaded.path("urls").path("get").asText(null);
            if (url == null || url.isBlank()) {
                throw new IOException("Replicate file upload returned no URL.");
            }
            return url;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Replicate file upload failed: " + e.getMessage(), e);
        }
    }

    private String pollUntilSucceeded(WebClient webClient, String predictionId)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            JsonNode p = webClient.get()
                    .uri("/v1/predictions/{id}", predictionId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(15));
            String status = p == null ? "unknown" : p.path("status").asText("unknown");
            if ("succeeded".equalsIgnoreCase(status)) {
                String output = p.path("output").asText(null);
                if (output == null || output.isBlank()) {
                    throw new IOException("Replicate prediction succeeded but produced no output URL.");
                }
                return output;
            }
            if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                throw new IOException("Replicate prediction " + status + ": "
                        + p.path("error").asText("no error detail"));
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new IOException("Replicate prediction timed out after " + (POLL_TIMEOUT_MS / 1000) + "s.");
    }

    private void downloadBinary(WebClient webClient, String url, Path target) throws IOException {
        byte[] bytes = webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(120));
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Replicate returned an empty video payload.");
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

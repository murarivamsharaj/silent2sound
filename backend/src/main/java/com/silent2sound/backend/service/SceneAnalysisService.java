package com.silent2sound.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Describes a scene by sending extracted frames to a vision model, falling
 * back to a canned descriptive prompt in mock mode or when no API key is set.
 */
@Service
@Slf4j
public class SceneAnalysisService {

    private static final String FALLBACK_PROMPT =
            "City park, windy trees, distant traffic, birds chirping";
    private static final String VISION_PROMPT =
            "Describe the environmental sounds and action sounds for this scene in 20 words "
                    + "for an audio generator.";
    private static final int MAX_FRAMES_SENT = 3;

    private final WebClient webClient;

    @Value("${app.ai.provider}")
    private String provider;

    @Value("${app.ai.vision.api-key}")
    private String visionApiKey;

    public SceneAnalysisService() {
        this.webClient = WebClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .build();
    }

    public String analyzeFramesAndGeneratePrompt(List<Path> framePaths, String userPromptOverride) {
        String visionPrompt;
        if (isMockProvider() || isBlank(visionApiKey) || framePaths == null || framePaths.isEmpty()) {
            visionPrompt = FALLBACK_PROMPT;
            log.info("Scene analysis using fallback prompt (provider={}, keyPresent={}, frames={})",
                    provider, !isBlank(visionApiKey), framePaths == null ? 0 : framePaths.size());
        } else {
            try {
                visionPrompt = callVisionApi(framePaths);
            } catch (Exception e) {
                log.warn("Vision API call failed, falling back to default prompt: {}", e.getMessage());
                visionPrompt = FALLBACK_PROMPT;
            }
        }

        if (userPromptOverride != null && !userPromptOverride.isBlank()) {
            return visionPrompt + ". " + userPromptOverride.trim();
        }
        return visionPrompt;
    }

    private boolean isMockProvider() {
        return "mock".equalsIgnoreCase(provider);
    }

    private String callVisionApi(List<Path> framePaths) throws IOException, InterruptedException {
        List<Path> selected = framePaths.stream()
                .sorted()
                .limit(MAX_FRAMES_SENT)
                .toList();

        ArrayNode content = buildContent(VISION_PROMPT, selected);

        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", "gpt-4o-mini");
        payload.set("messages", mapper.createArrayNode()
                .add(mapper.createObjectNode()
                        .put("role", "user")
                        .set("content", content)));
        payload.put("max_tokens", 80);

        JsonNode response = webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + visionApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload.toString())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        String description = response == null ? null
                : response.path("choices").path(0).path("message").path("content").asText(null);
        if (description == null || description.isBlank()) {
            throw new IOException("Vision API returned an empty description.");
        }
        return description.trim();
    }

    private static ArrayNode buildContent(String textPrompt, List<Path> frames) throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        ArrayNode content = mapper.createArrayNode();
        ObjectNode textPart = mapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", textPrompt);
        content.add(textPart);

        for (Path frame : frames) {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(frame));
            ObjectNode imagePart = mapper.createObjectNode();
            imagePart.put("type", "image_url");
            ((ObjectNode) imagePart.putObject("image_url"))
                    .put("url", "data:image/jpeg;base64," + base64);
            content.add(imagePart);
        }
        return content;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

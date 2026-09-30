package com.silent2sound.backend.service;

import com.silent2sound.backend.dto.SceneAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Describes a scene and decides whether people (potential speakers) appear,
 * by sending extracted frames to a vision model. Falls back to a canned
 * descriptive prompt plus keyword heuristics in mock mode or when no API key
 * is configured.
 */
@Service
@Slf4j
public class SceneAnalysisService {

    private static final String FALLBACK_PROMPT =
            "City park, windy trees, distant traffic, birds chirping";
    private static final String MOCK_DIALOGUE_LINE = "Hello, can you hear me?";
    private static final String VISION_PROMPT =
            "Describe the environmental sounds and action sounds for this scene in 20 words "
                    + "for an audio generator. Then on a new line answer strictly with "
                    + "'PEOPLE: yes' or 'PEOPLE: no' depending on whether any people who might "
                    + "be speaking are visible.";
    private static final int MAX_FRAMES_SENT = 3;

    /** Keywords in the user prompt override that imply human speakers. */
    private static final List<String> PEOPLE_KEYWORDS = List.of(
            "talk", "talking", "talks", "people", "person", "man", "woman", "speech",
            "speak", "speaking", "voice", "dialogue", "conversation", "interview");

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

    public SceneAnalysisResult analyzeFrames(List<Path> framePaths, String userPromptOverride) {
        if (isMockProvider() || isBlank(visionApiKey) || framePaths == null || framePaths.isEmpty()) {
            return mockAnalysis(userPromptOverride);
        }

        try {
            return visionAnalysis(framePaths, userPromptOverride);
        } catch (Exception e) {
            log.warn("Vision API call failed, falling back to default prompt: {}", e.getMessage());
            SceneAnalysisResult fallback = mockAnalysis(userPromptOverride);
            return SceneAnalysisResult.builder()
                    .scenePrompt(FALLBACK_PROMPT + appendOverride(userPromptOverride))
                    .hasPeople(fallback.isHasPeople())
                    .suggestedDialogue(fallback.getSuggestedDialogue())
                    .build();
        }
    }

    /**
     * Mock/heuristic path: keyword detection on the user's prompt override
     * decides whether speakers are implied; a canned dialogue line is offered.
     */
    private SceneAnalysisResult mockAnalysis(String userPromptOverride) {
        log.info("Scene analysis using fallback prompt (provider={}, keyPresent={})",
                provider, !isBlank(visionApiKey));

        boolean hasPeople = mentionsPeople(userPromptOverride);
        String scenePrompt = FALLBACK_PROMPT + appendOverride(userPromptOverride);

        return SceneAnalysisResult.builder()
                .scenePrompt(scenePrompt)
                .hasPeople(hasPeople)
                .suggestedDialogue(hasPeople ? MOCK_DIALOGUE_LINE : null)
                .build();
    }

    private SceneAnalysisResult visionAnalysis(List<Path> framePaths, String userPromptOverride)
            throws IOException, InterruptedException {
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
        payload.put("max_tokens", 120);

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

        // Split the description from the trailing "PEOPLE: yes/no" verdict.
        String flattened = description.trim();
        boolean hasPeople = flattened.toLowerCase(Locale.ROOT).contains("people: yes");
        String scenePrompt = flattened.replaceAll("(?i)people:\\s*(yes|no)", "").trim();

        // The model may invent dialogue; otherwise synthesize one when people
        // are present, and always honour an explicit user override.
        boolean overrideImpliesPeople = mentionsPeople(userPromptOverride);
        if (overrideImpliesPeople) {
            hasPeople = true;
        }
        String dialogue = hasPeople ? MOCK_DIALOGUE_LINE : null;

        return SceneAnalysisResult.builder()
                .scenePrompt(scenePrompt + appendOverride(userPromptOverride))
                .hasPeople(hasPeople)
                .suggestedDialogue(dialogue)
                .build();
    }

    private static boolean mentionsPeople(String userPromptOverride) {
        if (userPromptOverride == null || userPromptOverride.isBlank()) {
            return false;
        }
        String lower = userPromptOverride.toLowerCase(Locale.ROOT);
        return PEOPLE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String appendOverride(String userPromptOverride) {
        return (userPromptOverride != null && !userPromptOverride.isBlank())
                ? ". " + userPromptOverride.trim()
                : "";
    }

    private boolean isMockProvider() {
        return "mock".equalsIgnoreCase(provider);
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

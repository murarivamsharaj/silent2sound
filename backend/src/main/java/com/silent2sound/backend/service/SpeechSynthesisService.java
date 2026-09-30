package com.silent2sound.backend.service;

import lombok.RequiredArgsConstructor;
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
 * Produces the dialogue/voice track. In mock mode (or without an ElevenLabs
 * key) it renders a distinct 220 Hz placeholder tone via FFmpeg so the full
 * mixing pipeline is exercised locally without credentials.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpeechSynthesisService {

    private static final String MOCK_SPEECH_FREQUENCY = "220";

    private final FfmpegService ffmpegService;

    @Value("${app.ai.provider}")
    private String provider;

    @Value("${app.ai.elevenlabs.api-key}")
    private String elevenLabsApiKey;

    @Value("${app.ai.elevenlabs.voice-id}")
    private String elevenLabsVoiceId;

    public void generateSpeechTrack(String transcript, double durationSeconds, Path outputAudioPath)
            throws IOException, InterruptedException {

        if (!"elevenlabs".equalsIgnoreCase(provider) || isBlank(elevenLabsApiKey)) {
            log.info("Speech synthesis via FFmpeg placeholder tone (provider={}, keyPresent={})",
                    provider, !isBlank(elevenLabsApiKey));
            ffmpegService.generateToneAudio(outputAudioPath, durationSeconds, MOCK_SPEECH_FREQUENCY);
            return;
        }

        log.info("Speech synthesis via ElevenLabs TTS (voice={}, chars={})",
                elevenLabsVoiceId, transcript == null ? 0 : transcript.length());

        String payload = """
                {
                  "text": "%s",
                  "model_id": "eleven_monolingual_v1"
                }
                """.formatted(escapeJson(transcript));

        byte[] audio;
        try {
            WebClient webClient = WebClient.builder()
                    .baseUrl("https://api.elevenlabs.io")
                    .build();
            audio = webClient.post()
                    .uri("/v1/text-to-speech/{voiceId}", elevenLabsVoiceId)
                    .header("xi-api-key", elevenLabsApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(120));
        } catch (Exception e) {
            throw new IOException("ElevenLabs speech synthesis failed: " + e.getMessage(), e);
        }

        if (audio == null || audio.length == 0) {
            throw new IOException("ElevenLabs TTS returned an empty audio payload.");
        }

        Files.createDirectories(outputAudioPath.toAbsolutePath().getParent());
        Path tmp = outputAudioPath.resolveSibling(outputAudioPath.getFileName() + ".tmp");
        Files.write(tmp, audio);
        Files.move(tmp, outputAudioPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("ElevenLabs speech written to {} ({} bytes)", outputAudioPath, audio.length);
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
}

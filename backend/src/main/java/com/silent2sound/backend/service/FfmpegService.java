package com.silent2sound.backend.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Thin wrapper around a local FFmpeg binary. Resolves the executable from
 * ./tools (project-local static build), then the FFMPEG_PATH env var, then
 * the system PATH.
 */
@Service
@Slf4j
public class FfmpegService {

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Duration: (\\d+):(\\d{2}):(\\d{2})\\.(\\d+)");

    @Value("${app.storage.upload-dir}")
    private String uploadDir;

    private String ffmpegCommand = "ffmpeg";

    @PostConstruct
    void resolveBinary() {
        Optional<String> found = Stream.of(
                        System.getenv("FFMPEG_PATH"),
                        Paths.get("tools", "ffmpeg", "bin", "ffmpeg.exe").toString(),
                        Paths.get("tools", "ffmpeg", "bin", "ffmpeg").toString(),
                        Paths.get("..", "tools", "ffmpeg", "bin", "ffmpeg.exe").toString(),
                        Paths.get("..", "tools", "ffmpeg", "bin", "ffmpeg").toString())
                .filter(p -> p != null && Files.isRegularFile(Paths.get(p)))
                .findFirst();
        if (found.isPresent()) {
            ffmpegCommand = found.get();
            log.info("Using FFmpeg binary at {}", ffmpegCommand);
        } else {
            log.warn("No project-local FFmpeg found; falling back to system PATH lookup for 'ffmpeg'.");
        }
    }

    /**
     * Extracts one JPEG frame every {@code intervalSeconds} from the video.
     */
    public List<Path> extractSampleFrames(Path videoPath, Path outputDir, int intervalSeconds)
            throws IOException, InterruptedException {
        Files.createDirectories(outputDir);

        Path pattern = outputDir.resolve("frame_%03d.jpg");
        List<String> command = List.of(
                ffmpegCommand, "-y",
                "-i", videoPath.toAbsolutePath().toString(),
                "-vf", "fps=1/" + intervalSeconds,
                pattern.toAbsolutePath().toString());

        run(command, "frame extraction");
        return listFrames(outputDir);
    }

    /**
     * Generates a 440 Hz sine-wave AAC track, used as a stand-in for the
     * future AI-generated audio.
     */
    public void generateSyntheticToneAudio(Path outputPath, double durationSeconds)
            throws IOException, InterruptedException {
        generateToneAudio(outputPath, durationSeconds, "440");
    }

    /**
     * Generates a sine-wave AAC track at an arbitrary frequency; distinct
     * frequencies let tests tell the ambient and speech tracks apart.
     */
    public void generateToneAudio(Path outputPath, double durationSeconds, String frequency)
            throws IOException, InterruptedException {
        Files.createDirectories(outputPath.toAbsolutePath().getParent());

        List<String> command = List.of(
                ffmpegCommand, "-y",
                "-f", "lavfi",
                "-i", "sine=frequency=" + frequency + ":duration=" + durationSeconds,
                "-c:a", "aac",
                outputPath.toAbsolutePath().toString());

        run(command, "tone synthesis");
    }

    /**
     * Copies the video stream and re-encodes the audio into the output file.
     */
    public void muxVideoAndAudio(Path videoPath, Path audioPath, Path outputPath)
            throws IOException, InterruptedException {
        Files.createDirectories(outputPath.toAbsolutePath().getParent());

        List<String> command = List.of(
                ffmpegCommand, "-y",
                "-i", videoPath.toAbsolutePath().toString(),
                "-i", audioPath.toAbsolutePath().toString(),
                "-c:v", "copy",
                "-c:a", "aac",
                "-shortest",
                outputPath.toAbsolutePath().toString());

        run(command, "muxing");
    }

    /**
     * Two-track mix: ambient/foley ducked to 40% under the speech track so
     * dialogue stays intelligible, video stream copied untouched.
     */
    public void mixMultiTrackAudio(Path videoPath, Path ambientAudioPath, Path speechAudioPath,
                                   Path outputPath)
            throws IOException, InterruptedException {
        Files.createDirectories(outputPath.toAbsolutePath().getParent());

        List<String> command = List.of(
                ffmpegCommand, "-y",
                "-i", videoPath.toAbsolutePath().toString(),
                "-i", ambientAudioPath.toAbsolutePath().toString(),
                "-i", speechAudioPath.toAbsolutePath().toString(),
                "-filter_complex",
                "[1:a]volume=0.4[amb];[2:a]volume=1.0[spk];"
                        + "[amb][spk]amix=inputs=2:duration=first:dropout_transition=2[aout]",
                "-map", "0:v",
                "-map", "[aout]",
                "-c:v", "copy",
                "-c:a", "aac",
                "-shortest",
                outputPath.toAbsolutePath().toString());

        run(command, "multi-track mixing");
    }

    /**
     * Reads the container duration via ffprobe when available, otherwise by
     * parsing the Duration line from ffmpeg's stderr.
     */
    public Optional<Duration> probeDuration(Path videoPath) {
        String ffprobe = ffmpegCommand.replace("ffmpeg", "ffprobe");
        if (Files.isRegularFile(Paths.get(ffprobe))) {
            List<String> command = List.of(
                    ffprobe, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoPath.toAbsolutePath().toString());
            try {
                Process process = new ProcessBuilder(command).start();
                String out = readStream(process.getInputStream());
                if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return Optional.of(Duration.ofMillis((long) (Double.parseDouble(out.trim()) * 1000)));
                }
            } catch (Exception e) {
                log.warn("ffprobe failed for {}: {}", videoPath, e.getMessage());
            }
            return Optional.empty();
        }
        try {
            List<String> command = List.of(ffmpegCommand, "-i", videoPath.toAbsolutePath().toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = readStream(process.getInputStream());
            process.waitFor(15, TimeUnit.SECONDS);
            Matcher m = DURATION_PATTERN.matcher(out);
            if (m.find()) {
                long millis = Long.parseLong(m.group(1)) * 3_600_000L
                        + Long.parseLong(m.group(2)) * 60_000L
                        + Long.parseLong(m.group(3)) * 1_000L
                        + Long.parseLong(m.group(4)) * 100L;
                return Optional.of(Duration.ofMillis(millis));
            }
        } catch (Exception e) {
            log.warn("Duration probe failed for {}: {}", videoPath, e.getMessage());
        }
        return Optional.empty();
    }

    private void run(List<String> command, String stage) throws IOException, InterruptedException {
        log.info("FFmpeg [{}]: {}", stage, String.join(" ", command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = readStream(process.getInputStream());

        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("FFmpeg timed out during " + stage + ".");
        }
        if (process.exitValue() != 0) {
            throw new IOException("FFmpeg failed during " + stage
                    + " (exit " + process.exitValue() + "): " + tail(output));
        }
    }

    private static String readStream(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String tail(String output) {
        String flat = output.replace('\r', '\n').trim();
        String[] lines = flat.split("\n");
        if (lines.length <= 8) {
            return flat;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, lines.length - 8, lines.length));
    }

    private static List<Path> listFrames(Path outputDir) throws IOException {
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> frames = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().matches("frame_\\d{3}\\.jpg"))
                    .sorted()
                    .forEach(frames::add);
            return frames;
        }
    }
}

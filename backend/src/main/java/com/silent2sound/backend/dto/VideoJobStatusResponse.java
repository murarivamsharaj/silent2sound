package com.silent2sound.backend.dto;

import com.silent2sound.backend.model.JobStatus;
import com.silent2sound.backend.model.VideoJob;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class VideoJobStatusResponse {

    private UUID jobId;
    private JobStatus status;
    private String sceneDescription;
    private String errorMessage;
    private Boolean hasPeople;
    private String dialogueTranscript;
    private int progressPercentage;
    private String downloadUrl;

    public static VideoJobStatusResponse from(VideoJob job) {
        int progress = switch (job.getStatus()) {
            case PENDING -> 10;
            case ANALYZING -> 35;
            case GENERATING_AUDIO -> 65;
            case MERGING -> 90;
            case COMPLETED -> 100;
            case FAILED -> 0;
        };

        return VideoJobStatusResponse.builder()
                .jobId(job.getId())
                .status(job.getStatus())
                .sceneDescription(job.getSceneDescription())
                .errorMessage(job.getErrorMessage())
                .hasPeople(job.getHasPeople())
                .dialogueTranscript(job.getDialogueTranscript())
                .progressPercentage(progress)
                .downloadUrl(job.getStatus() == JobStatus.COMPLETED
                        ? "/api/v1/videos/download/" + job.getId()
                        : null)
                .build();
    }
}

package com.silent2sound.backend.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Structured output of scene analysis: the foley prompt plus whether people
 * (and therefore dialogue) are present.
 */
@Getter
@Builder
public class SceneAnalysisResult {

    /** Environmental / action foley prompt. */
    private String scenePrompt;

    /** True when people — likely speakers — are detected in the scene. */
    private boolean hasPeople;

    /** Plausible short line of speech when people are detected; null otherwise. */
    private String suggestedDialogue;
}

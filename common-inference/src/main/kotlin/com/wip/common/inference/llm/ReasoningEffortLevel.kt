package com.wip.common.inference.llm

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort

/**
 * Standard reasoning effort level supported by reasoning-capable LLMs (such as DeepSeek-R1, OpenAI o1/o3, Gemini Flash thinking).
 */
enum class ReasoningEffortLevel(val id: String) {
    DEFAULT("default"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    /**
     * Converts to Koog's OpenAI ReasoningEffort enum, returning null for DEFAULT so the model's default reasoning effort is used.
     */
    fun toKoogOpenAIEffort(): ReasoningEffort? = when (this) {
        DEFAULT -> null
        LOW -> ReasoningEffort.LOW
        MEDIUM -> ReasoningEffort.MEDIUM
        HIGH -> ReasoningEffort.HIGH
    }
}

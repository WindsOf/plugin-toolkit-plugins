package com.wip.ocrAI

/**
 * Centralized, modular classification instructions for OCR models (Qwen, Gemma, Anthropic, OpenAI).
 * Supports both pure text extraction mode (classifyText = false, default) and classified mode (classifyText = true).
 */
object OcrClassificationInstructions {

    /**
     * Core rule that anything inside a balloon/container must be treated as speech.
     */
    const val BALLOON_CONTAINER_SPEECH_RULE =
        "CRITICAL RULE: If text is inside a speech/thought balloon, bubble, or dedicated dialogue container, ALWAYS classify it as 'speech' (or {speech}) regardless of whether the text is a word, groan, moan, gasp, scream, sigh, sound-like utterance, or onomatopoeia (e.g. 'HAA...', 'HNNNGH', 'UGH', 'AAAH', 'KYAA', 'GASP')."

    /**
     * Classification rule block formatted for Qwen tagged output ({speech} vs {sfx}).
     */
    const val QWEN_CLASSIFICATION_RULES = """Classification:
   - Mark as {speech} if inside a speech bubble, thought balloon, or dialogue container.
     * $BALLOON_CONTAINER_SPEECH_RULE
   - Mark as {sfx} for sound effects, environmental onomatopoeia, stylized action lettering, impacts, and ambient sound words drawn freeform outside of speech balloons/containers."""

    /**
     * Classification rule block formatted for Koog / JSON models ('speech', 'sfx', 'none').
     */
    const val KOOG_CLASSIFICATION_RULES = """Classify each text area into 'category':
 - 'speech': regular dialogue, narration, spoken words, thoughts, and any character utterances (including screams, grunts, moans, gasps, sighs) enclosed within speech/thought balloons, speech bubbles, or dialogue boxes.
   * $BALLOON_CONTAINER_SPEECH_RULE
 - 'sfx': sound effects, environmental onomatopoeia, stylized action lettering, impacts, and ambient sound words drawn freeform outside of speech balloons/containers.
 - 'none': non-text artifacts, drawing details, or background textures."""

    /**
     * Builds the Qwen full-page OCR system prompt.
     */
    fun buildQwenSystemPrompt(classifyText: Boolean = false): String = if (classifyText) {
        """Task: Extract text, sound effects, and punctuation from this comic panel.

Rules:
1. $QWEN_CLASSIFICATION_RULES
2. Punctuation & Symbols:
   - Always extract standalone punctuation marks (such as "...", "?", "!", "?!", "—") if they appear as dialogue inside a speech bubble. Do not ignore them.

Example 1 (dialogue text in bubble):
{speech} [540, 30, 980, 270] WASHING THE DISHES

Example 2 (standalone punctuation in bubble):
{speech} [180, 350, 420, 450] ...

Example 3 (sound effect):
{sfx} [550, 314, 909, 470] THUMP

Output format:
{category} [x1, y1, x2, y2] extracted_text

Now process the image. Output only valid instances, one per line:"""
    } else {
        """Task: Extract text, dialogue, sound effects, and punctuation from this comic image.

Format:
[x1, y1, x2, y2] extracted_text

Rules:
1. Coordinates:
   - Output coordinates on a 1000x1000 scale [x1, y1, x2, y2] relative to the image.
2. Punctuation & Symbols:
   - Always extract standalone punctuation marks (such as "...", "?", "!", "?!", "—") if present. Do not ignore them.
3. Transcribe all text accurately as it appears.

Example 1:
[540, 30, 980, 270] WASHING THE DISHES

Example 2:
[180, 350, 420, 450] ...

Example 3:
[550, 314, 909, 470] THUMP

Output format:
[x1, y1, x2, y2] extracted_text

Now process the image. Output only valid instances, one per line:"""
    }

    /**
     * Builds the Qwen cropped-region OCR system prompt.
     */
    fun buildQwenCropSystemPrompt(classifyText: Boolean = false): String = if (classifyText) {
        """Task: Extract text, sound effects, and punctuation from this cropped comic region.

Rules:
1. $QWEN_CLASSIFICATION_RULES
2. Coordinates:
   - Output coordinates on a 1000x1000 scale [x1, y1, x2, y2] relative to this cropped image.
3. Punctuation & Symbols:
   - Always extract standalone punctuation marks ("...", "?", "!", "?!") if present.

Example 1 (dialogue text in bubble):
{speech} [540, 30, 980, 270] WASHING THE DISHES

Example 2 (sound effect):
{sfx} [550, 314, 909, 470] THUMP

Output format:
{category} [x1, y1, x2, y2] extracted_text

Now process the cropped image. Output only valid instances, one per line:"""
    } else {
        """Task: Extract text and punctuation from this cropped comic region.

Format:
[x1, y1, x2, y2] extracted_text

Rules:
1. Coordinates:
   - Output coordinates on a 1000x1000 scale [x1, y1, x2, y2] relative to this cropped image.
2. Punctuation & Symbols:
   - Always extract standalone punctuation marks (such as "...", "?", "!", "?!", "—") if present. Do not ignore them.
3. Transcribe all text accurately.

Example 1:
[540, 30, 980, 270] WASHING THE DISHES

Example 2:
[180, 350, 420, 450] ...

Output format:
[x1, y1, x2, y2] extracted_text

Now process the cropped image. Output only valid instances, one per line:"""
    }
}

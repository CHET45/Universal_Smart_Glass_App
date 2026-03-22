package com.fersaiyan.cyanbridge.localmodels

import com.fersaiyan.cyanbridge.localmodels.templates.PromptMessage
import com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateRegistryTest {
    @Test
    fun qwen_template_renders_turns_and_assistant_suffix() {
        val prompt = PromptTemplateRegistry.renderPrompt(
            templateId = "qwen_chat",
            systemPrompt = "You are concise.",
            messages = listOf(
                PromptMessage("User", "Hello"),
                PromptMessage("Assistant", "Hi"),
            ),
        )

        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("Hello"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun raw_template_falls_back_to_plain_transcript() {
        val prompt = PromptTemplateRegistry.renderPrompt(
            templateId = "raw_completion",
            systemPrompt = "",
            messages = listOf(PromptMessage("User", "Write one line")),
        )

        assertTrue(prompt.contains("User: Write one line"))
        assertTrue(prompt.endsWith("Assistant: "))
    }
}

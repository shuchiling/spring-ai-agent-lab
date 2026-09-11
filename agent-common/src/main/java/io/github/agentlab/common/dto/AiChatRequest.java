package io.github.agentlab.common.dto;


import jakarta.validation.constraints.NotBlank;

public record AiChatRequest(
        @NotBlank(message = "message must not be blank")
        String message) {
}
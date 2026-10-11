package de.agentcodi.core;

import java.util.Objects;

public final class ChatMessage {
    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    private final String id;
    private final Role role;
    private final StreamText text;
    private final boolean streaming;

    public ChatMessage(String id, Role role, String text, boolean streaming) {
        this(id, role, StreamText.redacted(text), streaming);
    }

    ChatMessage(String id, Role role, StreamText text, boolean streaming) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Chat message id must not be blank");
        }
        this.id = id;
        this.role = Objects.requireNonNull(role, "role");
        this.text = Objects.requireNonNull(text, "text");
        this.streaming = streaming;
    }

    public String getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getText() {
        return text.text();
    }

    StreamText streamText() {
        return text;
    }

    public boolean isStreaming() {
        return streaming;
    }
}

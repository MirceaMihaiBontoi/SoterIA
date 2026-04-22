package com.soteria.infrastructure.intelligence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a persistent emergency session.
 * Stores conversation history and the state of the AI feedback loop (RAG context).
 */
public class ChatSession {
    private String id;
    private long timestamp;
    private String title;
    private List<ChatMessage> messages;
    private Set<String> rejectedProtocolIds;
    private String contextualExtensions;
    private String activeEmergencyId;

    public ChatSession() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.title = "Emergencia - " + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        this.messages = new ArrayList<>();
        this.rejectedProtocolIds = new HashSet<>();
        this.contextualExtensions = "";
    }

    // Getters and Setters for Jackson serialization
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }

    public Set<String> getRejectedProtocolIds() { return rejectedProtocolIds; }
    public void setRejectedProtocolIds(Set<String> rejectedProtocolIds) { this.rejectedProtocolIds = rejectedProtocolIds; }

    public String getContextualExtensions() { return contextualExtensions; }
    public void setContextualExtensions(String contextualExtensions) { this.contextualExtensions = contextualExtensions; }

    public String getActiveEmergencyId() { return activeEmergencyId; }
    public void setActiveEmergencyId(String activeEmergencyId) { this.activeEmergencyId = activeEmergencyId; }
    
    public void addMessage(ChatMessage message) {
        this.messages.add(message);
    }
}

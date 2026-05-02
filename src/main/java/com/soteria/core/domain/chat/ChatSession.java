package com.soteria.core.domain.chat;

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
    private boolean protocolLocked = false;
    private java.util.Map<String, Integer> protocolProgress = new java.util.HashMap<>();
    private java.util.Map<String, String> requestedStepsMap = new java.util.HashMap<>();
    private java.util.Map<String, List<String>> categorizedContext = new java.util.HashMap<>();
    private Set<String> activeCategories = new HashSet<>();

    public ChatSession() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.title = null;
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

    public boolean isProtocolLocked() { return protocolLocked; }
    public void setProtocolLocked(boolean protocolLocked) { this.protocolLocked = protocolLocked; }

    public java.util.Map<String, Integer> getProtocolProgress() { return protocolProgress; }
    public void setProtocolProgress(java.util.Map<String, Integer> protocolProgress) { this.protocolProgress = protocolProgress; }

    public java.util.Map<String, String> getRequestedStepsMap() { return requestedStepsMap; }
    public void setRequestedStepsMap(java.util.Map<String, String> requestedStepsMap) { this.requestedStepsMap = requestedStepsMap; }

    public int getCurrentStepIndex() {
        if (activeEmergencyId == null) return 0;
        return protocolProgress.getOrDefault(activeEmergencyId, 0);
    }

    public void setCurrentStepIndex(int index) {
        if (activeEmergencyId != null) {
            protocolProgress.put(activeEmergencyId, index);
        }
    }

    public void incrementStepIndex(String protocolId) {
        if (protocolId != null) {
            int current = protocolProgress.getOrDefault(protocolId, 0);
            protocolProgress.put(protocolId, current + 1);
        }
    }
    
    public java.util.Map<String, List<String>> getCategorizedContext() { return categorizedContext; }
    public void setCategorizedContext(java.util.Map<String, List<String>> categorizedContext) { this.categorizedContext = categorizedContext; }

    public Set<String> getActiveCategories() { return activeCategories; }
    public void setActiveCategories(Set<String> activeCategories) { this.activeCategories = activeCategories; }

    public void addMessage(ChatMessage message) {
        this.messages.add(message);
    }

    public void addRejectedProtocolId(String protocolId) {
        if (protocolId != null && !protocolId.isBlank()) {
            this.rejectedProtocolIds.add(protocolId);
        }
    }
}

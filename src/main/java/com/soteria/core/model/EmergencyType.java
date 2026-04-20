package com.soteria.core.model;

/**
 * Abstract class that defines the contract for different types of emergencies.
 */
public abstract class EmergencyType {
    protected String name;
    protected int priority;  // 1-10, where 10 is maximum priority
    protected String description;
    protected boolean requiresMedicalAssistance;

    protected EmergencyType(String name, int priority, String description, boolean requiresMedicalAssistance) {
        this.name = name;
        this.priority = priority;
        this.description = description;
        this.requiresMedicalAssistance = requiresMedicalAssistance;
    }

    public int getPriority() {
        return priority;
    }

    public String getDescription() {
        return description;
    }

    public String getName() {
        return name;
    }

    public boolean requiresMedicalAssistance() {
        return requiresMedicalAssistance;
    }

    public abstract String getResponseProtocol();

    public abstract String[] getRequiredServices();

    @Override
    public String toString() {
        return String.format(
            "Emergency: %s%nPriority: %d/10%nDescription: %s%nMedical Assistance: %s",
            name, priority, description, 
            requiresMedicalAssistance ? "Yes" : "No"
        );
    }
}

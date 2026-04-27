package com.soteria.core.port;

import com.soteria.core.domain.emergency.Protocol;

import java.util.List;

public interface Triage {
    enum Intent {
        MEDICAL_EMERGENCY,
        SECURITY_EMERGENCY,
        ENVIRONMENTAL_EMERGENCY,
        TRAFFIC_EMERGENCY,
        UNKNOWN,
        INACTIVE,
        GREETING_OR_CASUAL
    }

    record TriageResult(Protocol protocol, float score, Intent intent) {
        public boolean isEmergency() {
            return protocol != null && score >= 0.30f;
        }
    }

    TriageResult classifyDynamic(String text, List<Protocol> candidates);
}

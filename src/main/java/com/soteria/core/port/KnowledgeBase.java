package com.soteria.core.port;

import com.soteria.core.domain.emergency.Protocol;

import java.util.List;
import java.util.Set;

public interface KnowledgeBase {
    record ProtocolMatch(Protocol protocol, String source, float score) {}

    List<ProtocolMatch> findProtocols(String query, Set<String> rejectedIds, boolean searchPrinciplesOnly);

    Protocol getProtocolById(String id);
}

package com.soteria.core.exception;

/**
 * Thrown when an AI engine (LocalBrain, STT, etc.) fails to initialize or execute.
 */
public class AIEngineException extends RuntimeException {
    public AIEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}

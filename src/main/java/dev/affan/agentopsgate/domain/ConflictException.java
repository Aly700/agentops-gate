package dev.affan.agentopsgate.domain;

public final class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

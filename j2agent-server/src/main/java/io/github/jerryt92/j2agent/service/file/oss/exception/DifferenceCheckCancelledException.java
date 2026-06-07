package io.github.jerryt92.j2agent.service.file.oss.exception;

public class DifferenceCheckCancelledException extends RuntimeException {
    public DifferenceCheckCancelledException() {
        super("difference check was cancelled");
    }
}

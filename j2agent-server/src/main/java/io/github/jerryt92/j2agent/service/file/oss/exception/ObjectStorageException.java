package io.github.jerryt92.j2agent.service.file.oss.exception;

/**
 * 屏蔽不同对象存储 SDK 的异常类型。
 */
public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.github.jerryt92.j2agent.utils;

import java.security.NoSuchAlgorithmException;

public final class UserUtil {
    public static String getPasswordHash(String userId, String password) {
        try {
            return HashUtil.getMessageDigest((userId + password).getBytes(), HashUtil.MdAlgorithm.SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean verifyPassword(String userId, String password, String passwordHash) {
        return getPasswordHash(userId, password).equals(passwordHash);
    }
}

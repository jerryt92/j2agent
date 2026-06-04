package io.github.jerryt92.j2agent.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 信息摘要工具类
 *
 * @author jerryt92.github.io
 * @date 2022/6/20
 */
public final class HashUtil {
    /**
     * 信息摘要
     *
     * @param data
     * @param algorithm 可选：MD2 / MD5 / SHA-1 / SHA-224 / SHA-256 / SHA-384 / SHA-512
     * @return messageDigest
     */
    public static String getMessageDigest(byte[] data, MdAlgorithm algorithm) throws NoSuchAlgorithmException {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm.value);
            byte[] bytes = md.digest(data);
            // 将字节数据转换为十六进制
            for (byte b : bytes) {
                stringBuilder.append(String.format("%02x", b));
            }
            return stringBuilder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw e;
        }
    }

    public enum MdAlgorithm {
        MD5("MD5"),
        SHA1("SHA-1"),
        SHA256("SHA-256"),
        SHA384("SHA-384"),
        SHA512("SHA-512");

        private String value;

        MdAlgorithm(String value) {
            this.value = value;
        }
    }

    /**
     * 将32字节的MD5转换为16字节
     *
     * @param md5_32B
     * @return
     */
    public static String transMd5To16(String md5_32B) {
        return md5_32B.substring(8, 24);
    }
}

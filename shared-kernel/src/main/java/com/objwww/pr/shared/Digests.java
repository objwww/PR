package com.objwww.pr.shared;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * digest 工具（shared-kernel 允许的纯工具；零框架依赖）。
 */
public final class Digests {

    private Digests() {
    }

    /** SHA-256 → 64 位小写 hex */
    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 字节级 SHA-256（M1-T03）：webhook payload_digest 必须对线上原始字节计算——
     * HMAC 验签的对象就是这批字节（CT-18），先转 String 会在非法 UTF-8 上失真。
     */
    public static String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

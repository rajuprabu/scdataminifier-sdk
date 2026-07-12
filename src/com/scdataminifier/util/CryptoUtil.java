package com.scdataminifier.util;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.scdataminifier.ScDataException;

/**
 * AES-GCM helpers. The wire format is a 12-byte random IV followed by
 * ciphertext + 16-byte GCM authentication tag.
 *
 * These are convenience helpers for applications that hold raw AES keys;
 * the SDK's parser itself only ever sees the DecryptionCallback.
 */
public final class CryptoUtil {

    public static final int GCM_IV_LENGTH = 12;
    public static final int GCM_TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {}

    /** @return 12-byte IV followed by ciphertext+tag */
    public static byte[] aesGcmEncrypt(byte[] key, byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[GCM_IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_LENGTH);
            System.arraycopy(ct, 0, out, GCM_IV_LENGTH, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new ScDataException("AES-GCM encryption failed", e);
        }
    }

    /** @param ivAndCiphertext 12-byte IV followed by ciphertext+tag */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] ivAndCiphertext) {
        if (ivAndCiphertext == null || ivAndCiphertext.length <= GCM_IV_LENGTH) {
            throw new ScDataException("Encrypted data too short to contain IV and ciphertext");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, ivAndCiphertext, 0, GCM_IV_LENGTH);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
            return cipher.doFinal(ivAndCiphertext, GCM_IV_LENGTH, ivAndCiphertext.length - GCM_IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new ScDataException("AES-GCM decryption failed (wrong key or corrupted data)", e);
        }
    }
}

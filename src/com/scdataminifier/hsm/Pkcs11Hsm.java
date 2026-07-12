package com.scdataminifier.hsm;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import com.scdataminifier.ScDataException;
import com.scdataminifier.ScHeader;
import com.scdataminifier.callback.DecryptionCallback;
import com.scdataminifier.callback.EncryptionCallback;
import com.scdataminifier.callback.SignatureVerifier;
import com.scdataminifier.callback.Signer;
import com.scdataminifier.enums.SignatureAlgorithm;

/**
 * SunPKCS11-backed HSM session producing ready-made SDK callbacks.
 * Keys never leave the token; all operations run inside the HSM through
 * the JCE provider.
 *
 * <pre>
 * // pkcs11.cfg:  name = MyHsm
 * //              library = /path/to/vendor-pkcs11.so
 * //              slot = 0
 * Pkcs11Hsm hsm = Pkcs11Hsm.open("/path/to/pkcs11.cfg", "1234".toCharArray());
 *
 * writer.withSigner(SignatureAlgorithm.ECDSA_P256_SHA256, 3,
 *                   hsm.signer(SignatureAlgorithm.ECDSA_P256_SHA256, "sign-key"));
 * writer.withEncryption(EncryptionType.AES_256, 7, hsm.encryptor("aes-key"));
 *
 * ScData data = ScDataParser.parse(payload,
 *                   hsm.decryptor("aes-key"), hsm.verifier("sign-key"));
 * </pre>
 *
 * Requires Java 9+ (uses Provider.configure); the project targets Java 17.
 */
public class Pkcs11Hsm {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Provider provider;
    private final KeyStore keyStore;
    private final char[] pin;

    private Pkcs11Hsm(Provider provider, KeyStore keyStore, char[] pin) {
        this.provider = provider;
        this.keyStore = keyStore;
        this.pin = pin;
    }

    /**
     * Loads the SunPKCS11 provider from the given config file and logs into
     * the token with the PIN.
     */
    public static Pkcs11Hsm open(String configPath, char[] pin) {
        Provider provider = loadProvider(configPath);
        try {
            KeyStore ks = KeyStore.getInstance("PKCS11", provider);
            ks.load(null, pin);
            return new Pkcs11Hsm(provider, ks, pin);
        } catch (Exception e) {
            Security.removeProvider(provider.getName());
            throw new ScDataException("PKCS11 token login failed (check PIN and slot)", e);
        }
    }

    private static Provider loadProvider(String configPath) {
        Provider base = Security.getProvider("SunPKCS11");
        if (base == null) {
            throw new ScDataException("SunPKCS11 provider not available in this JRE");
        }
        try {
            Provider configured = base.configure(configPath);
            Security.addProvider(configured);
            return configured;
        } catch (Exception e) {
            throw new ScDataException("Failed to load SunPKCS11 provider from config: " + configPath, e);
        }
    }

    // ==================== token access ====================

    public Provider getProvider() { return provider; }

    public KeyStore getKeyStore() { return keyStore; }

    public List<String> getAliases() {
        try {
            List<String> out = new ArrayList<String>();
            for (Enumeration<String> e = keyStore.aliases(); e.hasMoreElements();) out.add(e.nextElement());
            return out;
        } catch (GeneralSecurityException e) {
            throw new ScDataException("Failed to list token aliases", e);
        }
    }

    public PrivateKey getPrivateKey(String alias) {
        try {
            Key k = keyStore.getKey(alias, pin);
            if (!(k instanceof PrivateKey)) throw new ScDataException("No private key under alias '" + alias + "'");
            return (PrivateKey) k;
        } catch (GeneralSecurityException e) {
            throw new ScDataException("Failed to access private key '" + alias + "'", e);
        }
    }

    public SecretKey getSecretKey(String alias) {
        try {
            Key k = keyStore.getKey(alias, pin);
            if (!(k instanceof SecretKey)) throw new ScDataException("No secret key under alias '" + alias + "'");
            return (SecretKey) k;
        } catch (GeneralSecurityException e) {
            throw new ScDataException("Failed to access secret key '" + alias + "'", e);
        }
    }

    public PublicKey getPublicKey(String alias) {
        try {
            Certificate cert = keyStore.getCertificate(alias);
            if (cert == null) throw new ScDataException("No certificate under alias '" + alias + "'");
            return cert.getPublicKey();
        } catch (GeneralSecurityException e) {
            throw new ScDataException("Failed to access certificate '" + alias + "'", e);
        }
    }

    /** Unregisters the provider. Call when done with the token. */
    public void close() {
        Security.removeProvider(provider.getName());
    }

    // ==================== SDK callback factories ====================

    /** Signer running inside the HSM. Pass to ScDataWriter.withSigner(). */
    public Signer signer(final SignatureAlgorithm algorithm, final String privateKeyAlias) {
        if (algorithm == null || algorithm == SignatureAlgorithm.NONE) {
            throw new ScDataException("Signature algorithm must not be NONE");
        }
        return new Signer() {
            @Override
            public byte[] sign(byte[] dataToSign) {
                try {
                    Signature s = Signature.getInstance(algorithm.getJcaName(), provider);
                    s.initSign(getPrivateKey(privateKeyAlias));
                    s.update(dataToSign);
                    return s.sign();
                } catch (GeneralSecurityException e) {
                    throw new ScDataException("HSM signing failed with key '" + privateKeyAlias + "'", e);
                }
            }
        };
    }

    /**
     * Verifier using the certificate stored under the alias. Pass to
     * ScDataParser.parse(). For key-version-based key selection, implement
     * SignatureVerifier directly on top of getPublicKey().
     */
    public SignatureVerifier verifier(final String certificateAlias) {
        return new SignatureVerifier() {
            @Override
            public boolean verify(ScHeader header, SignatureAlgorithm algorithm, int keyVersion,
                                  byte[] signedData, byte[] signature) {
                try {
                    Signature s = Signature.getInstance(algorithm.getJcaName(), provider);
                    s.initVerify(getPublicKey(certificateAlias));
                    s.update(signedData);
                    return s.verify(signature);
                } catch (GeneralSecurityException e) {
                    return false;
                }
            }
        };
    }

    /** AES-GCM encryptor running inside the HSM. Pass to ScDataWriter.withEncryption(). */
    public EncryptionCallback encryptor(final String secretKeyAlias) {
        return new EncryptionCallback() {
            @Override
            public byte[] encrypt(ScHeader header, byte[] plaintext) {
                return aesGcmEncrypt(secretKeyAlias, plaintext);
            }
        };
    }

    /** AES-GCM decryptor running inside the HSM. Pass to ScDataParser.parse(). */
    public DecryptionCallback decryptor(final String secretKeyAlias) {
        return new DecryptionCallback() {
            @Override
            public byte[] decrypt(ScHeader header, byte[] ivAndCiphertext) {
                return aesGcmDecrypt(secretKeyAlias, ivAndCiphertext);
            }
        };
    }

    // ==================== AES-GCM on the token ====================

    private byte[] aesGcmEncrypt(String alias, byte[] plaintext) {
        try {
            SecretKey key = getSecretKey(alias);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", provider);
            byte[] iv = new byte[12];
            try {
                RANDOM.nextBytes(iv);
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            } catch (GeneralSecurityException e) {
                // Some (FIPS) tokens refuse a caller-supplied GCM IV and generate their own.
                cipher.init(Cipher.ENCRYPT_MODE, key);
                iv = cipher.getIV();
                if (iv == null || iv.length != 12) {
                    throw new ScDataException("HSM generated a GCM IV of unsupported length"
                            + (iv == null ? " (none)" : " " + iv.length) + "; format requires 12 bytes");
                }
            }
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new ScDataException("HSM encryption failed with key '" + alias + "'", e);
        }
    }

    private byte[] aesGcmDecrypt(String alias, byte[] ivAndCiphertext) {
        if (ivAndCiphertext == null || ivAndCiphertext.length <= 12) {
            throw new ScDataException("Encrypted data too short to contain IV and ciphertext");
        }
        try {
            SecretKey key = getSecretKey(alias);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", provider);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, ivAndCiphertext, 0, 12));
            return cipher.doFinal(ivAndCiphertext, 12, ivAndCiphertext.length - 12);
        } catch (GeneralSecurityException e) {
            throw new ScDataException("HSM decryption failed with key '" + alias + "' (wrong key or corrupted data)", e);
        }
    }
}

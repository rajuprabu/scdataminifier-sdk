package com.scdataminifier.demo;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

import com.scdataminifier.ScData;
import com.scdataminifier.ScDataException;
import com.scdataminifier.ScDataParser;
import com.scdataminifier.ScDataWriter;
import com.scdataminifier.ScHeader;
import com.scdataminifier.callback.DecryptionCallback;
import com.scdataminifier.callback.EncryptionCallback;
import com.scdataminifier.callback.SignatureVerifier;
import com.scdataminifier.callback.Signer;
import com.scdataminifier.enums.CompressionType;
import com.scdataminifier.enums.ContentType;
import com.scdataminifier.enums.EncryptionType;
import com.scdataminifier.enums.SignatureAlgorithm;
import com.scdataminifier.enums.TagType;
import com.scdataminifier.model.Content;
import com.scdataminifier.model.Table;
import com.scdataminifier.model.TableCell;
import com.scdataminifier.model.Tlv;
import com.scdataminifier.model.Variable;
import com.scdataminifier.util.CryptoUtil;

/**
 * End-to-end demo: builds a signed + encrypted payload, parses it back with
 * decryption/verification callbacks, then shows the unsigned CRC16 fallback
 * catching corruption.
 */
public class SCDataMinifierDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== SCDataMinifier demo ===");

        // Keys - in a real application these come from your key management.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair signingKeys = kpg.generateKeyPair();
        final byte[] aesKey = new byte[32];
        new SecureRandom().nextBytes(aesKey);

        // ---------- 1. Build a signed + encrypted payload ----------
        ScDataWriter w = new ScDataWriter(0x0102, 987654321L, 1);

        w.startTlvContent()
                .addCaption("Employee Record")
                .addString("John Doe", 1)              // tag ID 1
                .addUnicodeString("जॉन डो", 2) // Devanagari text, tag ID 2
                .addInteger(35, 3)
                .addFloat(72.5f, 4)
                .startTable(3, 3, true)                // header row
                        .setCaption(0, 0, "Item").setCaption(0, 1, "Qty").setCaption(0, 2, "Price")
                        .setString(1, 0, "USB-C Cable").setInteger(1, 1, 2).setFloat(1, 2, 12.0f)
                        .setString(2, 0, "Charger").setFloat(2, 2, 25.0f) // Qty cell left empty on purpose
                .endTable()
                .endContent();

        w.startVariablesContent()
                .addString(0, "Raju")
                .addInteger(1, 2026)
                .endContent();

        w.startCompressedTlvContent(CompressionType.ZIP)
                .addUnicodeString(repeat("Compressed content demo. ", 20))
                .endContent();

        w.withSigner(SignatureAlgorithm.ECDSA_P256_SHA256, 3, new Signer() {
            @Override
            public byte[] sign(byte[] dataToSign) {
                try {
                    Signature s = Signature.getInstance("SHA256withECDSA");
                    s.initSign(signingKeys.getPrivate());
                    s.update(dataToSign);
                    return s.sign();
                } catch (GeneralSecurityException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        w.withEncryption(EncryptionType.AES_256, 7, new EncryptionCallback() {
            @Override
            public byte[] encrypt(ScHeader header, byte[] plaintext) {
                System.out.println("Encrypt callback: " + header.getEncryptionType()
                        + ", keyVersion=" + header.getEncryptionKeyVersion());
                return CryptoUtil.aesGcmEncrypt(aesKey, plaintext);
            }
        });

        byte[] payload = w.build();
        System.out.println("Signed+encrypted payload: " + payload.length
                + " bytes (QR limit " + ScDataWriter.MAX_QR_PAYLOAD + ")");

        // ---------- 2. Parse with decryption + verification callbacks ----------
        ScData data = ScDataParser.parse(payload,
                new DecryptionCallback() {
                    @Override
                    public byte[] decrypt(ScHeader header, byte[] ivAndCiphertext) {
                        System.out.println("Decrypt callback: " + header.getEncryptionType()
                                + ", keyVersion=" + header.getEncryptionKeyVersion());
                        return CryptoUtil.aesGcmDecrypt(aesKey, ivAndCiphertext);
                    }
                },
                new SignatureVerifier() {
                    @Override
                    public boolean verify(ScHeader header, SignatureAlgorithm algorithm, int keyVersion,
                                          byte[] signedData, byte[] signature) {
                        try {
                            Signature s = Signature.getInstance(algorithm.getJcaName());
                            s.initVerify(signingKeys.getPublic());
                            s.update(signedData);
                            boolean ok = s.verify(signature);
                            System.out.println("Verify callback: " + algorithm + ", keyVersion=" + keyVersion + " -> " + ok);
                            return ok;
                        } catch (GeneralSecurityException e) {
                            return false;
                        }
                    }
                });

        printData(data);

        // ---------- 3. Unsigned + unencrypted payload falls back to CRC16 ----------
        ScDataWriter w2 = new ScDataWriter(0x0102, 111L, 1);
        w2.startTlvContent().addString("Plain payload").endContent();
        byte[] plain = w2.build();
        System.out.println("\nPlain payload: " + plain.length + " bytes");
        ScData plainData = ScDataParser.parse(plain);
        System.out.println("Plain parse OK: \"" + plainData.getContents().get(0).getTlvs().get(0).asString() + "\"");

        byte[] corrupted = plain.clone();
        corrupted[20] ^= 0x01; // flip one bit inside the string value
        try {
            ScDataParser.parse(corrupted);
            System.out.println("ERROR: corruption was not detected!");
        } catch (ScDataException e) {
            System.out.println("Corruption detected as expected: " + e.getMessage());
        }
    }

    // ==================== printing helpers ====================

    private static void printData(ScData data) {
        ScHeader h = data.getHeader();
        System.out.println("\nHeader: version=" + h.getVersion()
                + " appId=0x" + Integer.toHexString(h.getApplicationId())
                + " uniqueId=" + h.getUniqueId()
                + " minClientVersion=" + h.getMinClientVersion()
                + " encrypted=" + h.isEncrypted());
        int i = 0;
        for (Content c : data.getContents()) {
            System.out.println("Content " + (i++) + ": " + c.getType()
                    + (c.getCompression() != null ? " (" + c.getCompression() + ")" : ""));
            if (c.getType() == ContentType.VARIABLES) {
                for (Variable v : c.getVariables()) {
                    System.out.println("  var[" + v.getId() + "] " + v.getType() + " = " + displayVariable(v));
                }
            } else {
                for (Tlv t : c.getTlvs()) printTlv(t);
            }
        }
    }

    private static void printTlv(Tlv t) {
        String id = t.hasTagId() ? "[id=" + t.getTagId() + "]" : "";
        if (t.getType() == TagType.TABLE) {
            Table tb = t.asTable();
            System.out.println("  TABLE" + id + " " + tb.getRows() + "x" + tb.getCols()
                    + (tb.hasHeaderRow() ? " (header row)" : ""));
            for (int r = 0; r < tb.getRows(); r++) {
                StringBuilder row = new StringBuilder("    |");
                for (int c = 0; c < tb.getCols(); c++) {
                    TableCell cell = tb.getCell(r, c);
                    row.append(' ').append(cell == null ? "-" : displayCell(cell)).append(" |");
                }
                System.out.println(row);
            }
        } else {
            System.out.println("  " + t.getType() + id + " = " + displayTlv(t));
        }
    }

    private static String displayTlv(Tlv t) {
        switch (t.getType()) {
            case CAPTION:
            case STRING:
            case UNICODE_STRING: return truncate(t.asString());
            case INTEGER: return String.valueOf(t.asInt());
            case FLOAT: return String.valueOf(t.asFloat());
            default: return t.getValue().length + " bytes";
        }
    }

    private static String displayCell(TableCell c) {
        switch (c.getType()) {
            case CAPTION:
            case STRING:
            case UNICODE_STRING: return c.asString();
            case INTEGER: return String.valueOf(c.asInt());
            case FLOAT: return String.valueOf(c.asFloat());
            default: return c.getValue().length + " bytes";
        }
    }

    private static String displayVariable(Variable v) {
        switch (v.getType()) {
            case CAPTION:
            case STRING:
            case UNICODE_STRING: return truncate(v.asString());
            case INTEGER: return String.valueOf(v.asInt());
            case FLOAT: return String.valueOf(v.asFloat());
            default: return v.getValue().length + " bytes";
        }
    }

    private static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}

package com.scdataminifier.demo;

import com.scdataminifier.ScData;
import com.scdataminifier.ScDataParser;
import com.scdataminifier.ScDataWriter;
import com.scdataminifier.enums.EncryptionType;
import com.scdataminifier.enums.SignatureAlgorithm;
import com.scdataminifier.hsm.Pkcs11Hsm;

/**
 * End-to-end demo against a real PKCS#11 token (vendor HSM, SoftHSM2, smart card).
 *
 * Usage:
 *   java com.scdataminifier.demo.HsmDemo <pkcs11-config-file> <pin> [signKeyAlias] [aesKeyAlias]
 *
 * Example pkcs11 config file:
 *   name = SoftHsm
 *   library = /opt/homebrew/lib/softhsm/libsofthsm2.so
 *   slot = <slot-id>
 *
 * Expected token contents (aliases default to "sign-key" and "aes-key"):
 *   - an EC P-256 key pair with certificate under the sign alias
 *   - an AES-256 secret key under the AES alias
 */
public class HsmDemo {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: HsmDemo <pkcs11-config-file> <pin> [signKeyAlias] [aesKeyAlias]");
            return;
        }
        String configPath = args[0];
        char[] pin = args[1].toCharArray();
        String signAlias = args.length > 2 ? args[2] : "sign-key";
        String aesAlias = args.length > 3 ? args[3] : "aes-key";

        Pkcs11Hsm hsm = Pkcs11Hsm.open(configPath, pin);
        try {
            System.out.println("Provider: " + hsm.getProvider().getName());
            System.out.println("Token aliases: " + hsm.getAliases());

            // pick the signature algorithm matching the key on the token
            String keyAlg = hsm.getPrivateKey(signAlias).getAlgorithm();
            SignatureAlgorithm sigAlg = "RSA".equals(keyAlg)
                    ? SignatureAlgorithm.RSA_2048_SHA256
                    : SignatureAlgorithm.ECDSA_P256_SHA256;
            System.out.println("Signing key '" + signAlias + "' is " + keyAlg + " -> " + sigAlg);

            // ---------- build: sign + encrypt entirely inside the HSM ----------
            ScDataWriter w = new ScDataWriter(0x0102, 555444333L, 1);
            w.startTlvContent()
                    .addCaption("HSM-protected record")
                    .addString("John Doe", 1)
                    .addInteger(35, 2)
                    .endContent();
            w.withSigner(sigAlg, 3, hsm.signer(sigAlg, signAlias));
            w.withEncryption(EncryptionType.AES_256, 7, hsm.encryptor(aesAlias));

            byte[] payload = w.build();
            System.out.println("Signed+encrypted payload via HSM: " + payload.length + " bytes");

            // ---------- parse: decrypt + verify entirely inside the HSM ----------
            ScData data = ScDataParser.parse(payload, hsm.decryptor(aesAlias), hsm.verifier(signAlias));
            System.out.println("Parse OK. appId=0x" + Integer.toHexString(data.getHeader().getApplicationId())
                    + " uniqueId=" + data.getHeader().getUniqueId());
            System.out.println("Name  = " + data.getContents().get(0).getTlvs().get(1).asString());
            System.out.println("Age   = " + data.getContents().get(0).getTlvs().get(2).asInt());
            System.out.println("HSM round-trip successful.");
        } finally {
            hsm.close();
        }
    }
}

package com.scdataminifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.scdataminifier.enums.ContentType;
import com.scdataminifier.enums.SignatureAlgorithm;
import com.scdataminifier.model.Content;
import com.scdataminifier.model.Variable;

/** Result of ScDataParser.parse(): header, contents and signature details. */
public class ScData {

    private final ScHeader header;
    private final List<Content> contents;
    private final SignatureAlgorithm signatureAlgorithm;
    private final int signatureKeyVersion;
    private final byte[] signature;   // null when unsigned (CRC16 payload)
    private final byte[] signedData;  // exact bytes the signature/CRC covers

    ScData(ScHeader header, List<Content> contents, SignatureAlgorithm signatureAlgorithm,
           int signatureKeyVersion, byte[] signature, byte[] signedData) {
        this.header = header;
        this.contents = contents;
        this.signatureAlgorithm = signatureAlgorithm;
        this.signatureKeyVersion = signatureKeyVersion;
        this.signature = signature;
        this.signedData = signedData;
    }

    public ScHeader getHeader() { return header; }

    public List<Content> getContents() { return Collections.unmodifiableList(contents); }

    public SignatureAlgorithm getSignatureAlgorithm() { return signatureAlgorithm; }

    public int getSignatureKeyVersion() { return signatureKeyVersion; }

    /** Signature bytes, or null when the payload was unsigned (CRC16-protected). */
    public byte[] getSignature() { return signature; }

    /**
     * The exact bytes the digital signature (or CRC16) covers - header bytes
     * 1-11 plus the content region. Use this to verify the signature outside
     * the parser callback.
     */
    public byte[] getSignedData() { return signedData; }

    // ==================== convenience ====================

    /** First variable with the given ID across all VARIABLES contents, or null. */
    public Variable getVariable(int id) {
        for (Content c : contents) {
            if (c.getType() != ContentType.VARIABLES) continue;
            for (Variable v : c.getVariables()) {
                if (v.getId() == id) return v;
            }
        }
        return null;
    }

    public List<Variable> getAllVariables() {
        List<Variable> out = new ArrayList<Variable>();
        for (Content c : contents) {
            if (c.getType() == ContentType.VARIABLES) out.addAll(c.getVariables());
        }
        return out;
    }
}

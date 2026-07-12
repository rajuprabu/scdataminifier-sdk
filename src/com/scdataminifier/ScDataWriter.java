package com.scdataminifier;

import java.util.ArrayList;
import java.util.List;

import com.scdataminifier.callback.EncryptionCallback;
import com.scdataminifier.callback.Signer;
import com.scdataminifier.enums.CompressionType;
import com.scdataminifier.enums.ContentType;
import com.scdataminifier.enums.EncryptionType;
import com.scdataminifier.enums.SignatureAlgorithm;
import com.scdataminifier.enums.TagType;
import com.scdataminifier.model.Content;
import com.scdataminifier.model.ScImage;
import com.scdataminifier.model.Table;
import com.scdataminifier.model.TableCell;
import com.scdataminifier.model.Tlv;
import com.scdataminifier.model.Variable;
import com.scdataminifier.util.ByteWriter;
import com.scdataminifier.util.CompressionUtil;
import com.scdataminifier.util.Crc16;

/**
 * Builder for SCDataMinifier payloads (format documented in SPEC.md).
 *
 * <pre>
 * ScDataWriter w = new ScDataWriter(appId, uniqueId, minClientVersion);
 * w.startTlvContent()
 *     .addString("John Doe", 1)
 *     .startTable(2, 2, true)
 *         .setCaption(0, 0, "Item").setCaption(0, 1, "Qty")
 *         .setString(1, 0, "Cable").setInteger(1, 1, 2)
 *     .endTable()
 * .endContent();
 * w.withSigner(SignatureAlgorithm.ECDSA_P256_SHA256, keyVer, mySigner);
 * w.withEncryption(EncryptionType.AES_256, keyVer, myEncryptionCallback);
 * byte[] payload = w.build();
 * </pre>
 *
 * Unsigned payloads automatically carry a CRC16 instead of a signature.
 */
public class ScDataWriter {

    public static final int MAGIC = 0x03;
    public static final int FORMAT_VERSION = 1;
    /** Binary capacity of the largest QR code (version 40, error correction level L). */
    public static final int MAX_QR_PAYLOAD = 2953;

    private final int applicationId;
    private final long uniqueId;
    private final int minClientVersion;
    private final List<Content> contents = new ArrayList<Content>();

    private EncryptionType encryptionType;
    private int encryptionKeyVersion;
    private EncryptionCallback encryptor;

    private SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.NONE;
    private int signatureKeyVersion;
    private byte[] signature;
    private Signer signer;

    private int dictionaryVersion = -1;
    private byte[] compressionDictionary;

    private int maxPayloadSize = MAX_QR_PAYLOAD;

    public ScDataWriter(int applicationId, long uniqueId, int minClientVersion) {
        if (applicationId < 0 || applicationId > 0xFFFF) throw new ScDataException("applicationId must fit in 2 bytes (0-65535)");
        if (uniqueId < 0 || uniqueId > 0xFFFFFFFFFFL) throw new ScDataException("uniqueId must fit in 5 bytes (0-1099511627775)");
        if (minClientVersion < 0 || minClientVersion > 0xFFFF) throw new ScDataException("minClientVersion must fit in 2 bytes (0-65535)");
        this.applicationId = applicationId;
        this.uniqueId = uniqueId;
        this.minClientVersion = minClientVersion;
    }

    // ==================== configuration ====================

    /** Payload size limit enforced by build(); defaults to MAX_QR_PAYLOAD. Pass 0 to disable. */
    public ScDataWriter setMaxPayloadSize(int bytes) {
        this.maxPayloadSize = bytes;
        return this;
    }

    /**
     * Shared preset dictionary for ZIP_DICT contents. The parser side must
     * supply the identical bytes for this version via DictionaryProvider.
     */
    public ScDataWriter withCompressionDictionary(int version, byte[] dictionary) {
        if (dictionary == null || dictionary.length == 0) throw new ScDataException("Dictionary is null/empty");
        if (version < 0 || version > 0xFF) throw new ScDataException("Dictionary version must be 0-255");
        this.dictionaryVersion = version;
        this.compressionDictionary = dictionary;
        return this;
    }

    /**
     * Enables encryption. The application-supplied callback performs the actual
     * AES-GCM encryption during build() and must return a 12-byte IV followed
     * by ciphertext+tag (CryptoUtil.aesGcmEncrypt() does exactly that); the
     * SDK never holds keys.
     */
    public ScDataWriter withEncryption(EncryptionType type, int keyVersion, EncryptionCallback encryptor) {
        if (type == null) throw new ScDataException("Encryption type is null");
        if (encryptor == null) throw new ScDataException("EncryptionCallback is null");
        if (keyVersion < 0 || keyVersion > 0xFFFF) throw new ScDataException("Encryption key version must be 0-65535");
        this.encryptionType = type;
        this.encryptionKeyVersion = keyVersion;
        this.encryptor = encryptor;
        return this;
    }

    /** Attaches a pre-computed signature over getBytesForSignature(). */
    public ScDataWriter withSignature(SignatureAlgorithm algorithm, int keyVersion, byte[] signatureBytes) {
        checkSignatureConfig(algorithm, keyVersion);
        if (signatureBytes == null) throw new ScDataException("Signature bytes are null");
        this.signatureAlgorithm = algorithm;
        this.signatureKeyVersion = keyVersion;
        this.signature = signatureBytes;
        this.signer = null;
        return this;
    }

    /** Signs during build() via the application-supplied callback. */
    public ScDataWriter withSigner(SignatureAlgorithm algorithm, int keyVersion, Signer signer) {
        checkSignatureConfig(algorithm, keyVersion);
        if (signer == null) throw new ScDataException("Signer is null");
        this.signatureAlgorithm = algorithm;
        this.signatureKeyVersion = keyVersion;
        this.signer = signer;
        this.signature = null;
        return this;
    }

    private static void checkSignatureConfig(SignatureAlgorithm algorithm, int keyVersion) {
        if (algorithm == null || algorithm == SignatureAlgorithm.NONE) throw new ScDataException("Signature algorithm must not be NONE");
        if (keyVersion < 0 || keyVersion > 15) throw new ScDataException("Signature key version must be 0-15");
    }

    // ==================== contents ====================

    public ScDataWriter addContent(Content content) {
        if (content == null) throw new ScDataException("Content is null");
        if (contents.size() >= 255) throw new ScDataException("At most 255 contents per payload");
        contents.add(content);
        return this;
    }

    public TlvContentBuilder startTlvContent() {
        return new TlvContentBuilder(null, false);
    }

    /** Always-compressed content (fails no smaller-size check). */
    public TlvContentBuilder startCompressedTlvContent(CompressionType compression) {
        if (compression == null) throw new ScDataException("Compression type is null");
        return new TlvContentBuilder(compression, false);
    }

    /**
     * Compresses with the given type, but emits a plain TLV content instead
     * when the compressed form is not smaller (e.g. short or high-entropy data).
     */
    public TlvContentBuilder startAutoCompressedTlvContent(CompressionType compression) {
        if (compression == null) throw new ScDataException("Compression type is null");
        return new TlvContentBuilder(compression, true);
    }

    public VariablesBuilder startVariablesContent() {
        return new VariablesBuilder();
    }

    // ==================== build ====================

    /**
     * The exact bytes covered by the digital signature (and by the CRC16 when
     * unsigned): header bytes 1-11 followed by the content region. Sign these
     * bytes externally and attach the result with withSignature().
     */
    public byte[] getBytesForSignature() {
        return signedData(encodeContentRegion());
    }

    public byte[] build() {
        byte[] contentRegion = encodeContentRegion();
        byte[] signedData = signedData(contentRegion);

        byte[] sig = signature;
        if (signatureAlgorithm != SignatureAlgorithm.NONE && sig == null) {
            if (signer == null) throw new ScDataException("No signature bytes and no Signer callback configured");
            sig = signer.sign(signedData);
            if (sig == null) throw new ScDataException("Signer returned null");
        }

        ByteWriter plain = new ByteWriter();
        plain.writeBytes(contentRegion);
        encodeSignatureBlock(plain, sig, signedData);
        byte[] plainRegion = plain.toBytes();

        ByteWriter out = new ByteWriter();
        writeHeader11(out);
        if (encryptionType == null) {
            out.writeByte(0x00);
            out.writeBytes(plainRegion);
        } else {
            ScHeader header = new ScHeader(FORMAT_VERSION, applicationId, uniqueId, minClientVersion,
                    true, encryptionType, encryptionKeyVersion);
            byte[] enc = encryptor.encrypt(header, plainRegion);
            if (enc == null) throw new ScDataException("EncryptionCallback returned null");
            if (enc.length > 0xFFFF) throw new ScDataException("Encrypted data exceeds 65535 bytes");
            int lenSize = enc.length > 0xFF ? 2 : 1;
            out.writeByte(0x01 | (encryptionType.getCode() << 1) | (lenSize == 2 ? 0x10 : 0x00));
            out.writeShort(encryptionKeyVersion);
            if (lenSize == 2) out.writeShort(enc.length); else out.writeByte(enc.length);
            out.writeBytes(enc);
        }

        byte[] payload = out.toBytes();
        if (maxPayloadSize > 0 && payload.length > maxPayloadSize) {
            throw new ScDataException("Payload is " + payload.length + " bytes, exceeding the limit of "
                    + maxPayloadSize + " (largest QR code holds " + MAX_QR_PAYLOAD
                    + " binary bytes); use setMaxPayloadSize(0) to disable this check");
        }
        return payload;
    }

    // ==================== encoding internals ====================

    private void writeHeader11(ByteWriter w) {
        w.writeByte(MAGIC)
         .writeByte(FORMAT_VERSION)
         .writeShort(applicationId)
         .writeLong5(uniqueId)
         .writeShort(minClientVersion);
    }

    private byte[] signedData(byte[] contentRegion) {
        ByteWriter w = new ByteWriter();
        writeHeader11(w);
        w.writeBytes(contentRegion);
        return w.toBytes();
    }

    private byte[] encodeContentRegion() {
        ByteWriter w = new ByteWriter();
        w.writeByte(contents.size());
        for (Content c : contents) encodeContent(w, c);
        return w.toBytes();
    }

    private void encodeContent(ByteWriter w, Content c) {
        switch (c.getType()) {
            case TLV:
                writeContentBlock(w, ContentType.TLV, encodeTlvList(c.getTlvs()));
                break;
            case COMPRESSED_TLV: {
                byte[] raw = encodeTlvList(c.getTlvs());
                CompressionType compression = c.getCompression();
                ByteWriter d = new ByteWriter();
                d.writeByte(compression.getCode() & 0x0F);
                if (compression == CompressionType.ZIP_DICT) {
                    if (compressionDictionary == null) {
                        throw new ScDataException("ZIP_DICT content requires withCompressionDictionary()");
                    }
                    d.writeByte(dictionaryVersion);
                    d.writeBytes(CompressionUtil.compress(raw, compression, compressionDictionary));
                } else {
                    d.writeBytes(CompressionUtil.compress(raw, compression));
                }
                byte[] compressed = d.toBytes();
                if (c.isAutoCompress() && compressed.length >= raw.length) {
                    writeContentBlock(w, ContentType.TLV, raw); // compression did not pay off
                } else {
                    writeContentBlock(w, ContentType.COMPRESSED_TLV, compressed);
                }
                break;
            }
            case VARIABLES: {
                ByteWriter d = new ByteWriter();
                for (Variable v : c.getVariables()) encodeVariable(d, v);
                writeContentBlock(w, ContentType.VARIABLES, d.toBytes());
                break;
            }
            default:
                throw new ScDataException("Unsupported content type: " + c.getType());
        }
    }

    private static void writeContentBlock(ByteWriter w, ContentType type, byte[] data) {
        if (data.length > 0xFFFF) throw new ScDataException("Content data exceeds 65535 bytes");
        int lenSize = data.length > 0xFF ? 2 : 1;
        w.writeByte(type.getCode() | (lenSize == 2 ? 0x10 : 0x00));
        if (lenSize == 2) w.writeShort(data.length); else w.writeByte(data.length);
        w.writeBytes(data);
    }

    private static byte[] encodeTlvList(List<Tlv> tlvs) {
        ByteWriter w = new ByteWriter();
        for (Tlv t : tlvs) encodeTlv(w, t);
        return w.toBytes();
    }

    private static void encodeTlv(ByteWriter w, Tlv t) {
        byte[] value = t.getType() == TagType.TABLE ? encodeTable(t.asTable()) : t.getValue();
        if (value.length > 0xFFFF) throw new ScDataException("TLV value exceeds 65535 bytes");
        int lenSize = value.length > 0xFF ? 2 : 1;
        int tagId = t.hasTagId() ? t.getTagId() : -1;
        int idSize = tagId < 0 ? 0 : (tagId > 0xFF ? 2 : 1);
        w.writeByte(t.getType().getCode()
                | (lenSize == 2 ? 0x10 : 0x00)
                | (tagId >= 0 ? 0x20 : 0x00)
                | (idSize == 2 ? 0x40 : 0x00));
        if (lenSize == 2) w.writeShort(value.length); else w.writeByte(value.length);
        if (idSize == 1) w.writeByte(tagId);
        else if (idSize == 2) w.writeShort(tagId);
        w.writeBytes(value);
    }

    private static byte[] encodeTable(Table t) {
        ByteWriter w = new ByteWriter();
        w.writeByte(((t.getRows() - 1) << 4) | (t.getCols() - 1));
        w.writeByte(t.hasHeaderRow() ? 0x01 : 0x00);
        for (int r = 0; r < t.getRows(); r++) {
            for (int c = 0; c < t.getCols(); c++) {
                TableCell cell = t.getCell(r, c);
                if (cell == null) {
                    w.writeByte(0x00); // data-present bit clear: no length, no value
                    continue;
                }
                byte[] v = cell.getValue();
                if (v.length > 0xFFFF) throw new ScDataException("Table cell value exceeds 65535 bytes");
                int lenSize = v.length > 0xFF ? 2 : 1;
                w.writeByte(cell.getType().getCode() | (lenSize == 2 ? 0x10 : 0x00) | 0x20);
                if (lenSize == 2) w.writeShort(v.length); else w.writeByte(v.length);
                w.writeBytes(v);
            }
        }
        return w.toBytes();
    }

    private static void encodeVariable(ByteWriter w, Variable v) {
        byte[] value = v.getValue();
        if (value.length > 0xFFFF) throw new ScDataException("Variable value exceeds 65535 bytes");
        int lenSize = value.length > 0xFF ? 2 : 1;
        w.writeByte(v.getType().getCode() | (lenSize == 2 ? 0x10 : 0x00));
        w.writeByte(v.getId() & 0x1F);
        if (lenSize == 2) w.writeShort(value.length); else w.writeByte(value.length);
        w.writeBytes(value);
    }

    private void encodeSignatureBlock(ByteWriter w, byte[] sig, byte[] signedData) {
        if (signatureAlgorithm == SignatureAlgorithm.NONE) {
            w.writeByte(SignatureAlgorithm.NONE.getCode());
            w.writeShort(Crc16.compute(signedData));
        } else {
            if (sig.length > 0xFFFF) throw new ScDataException("Signature exceeds 65535 bytes");
            int lenSize = sig.length > 0xFF ? 2 : 1;
            w.writeByte(signatureAlgorithm.getCode()
                    | ((signatureKeyVersion & 0x0F) << 3)
                    | (lenSize == 2 ? 0x80 : 0x00));
            if (lenSize == 2) w.writeShort(sig.length); else w.writeByte(sig.length);
            w.writeBytes(sig);
        }
    }

    // ==================== fluent builders ====================

    public class TlvContentBuilder {
        private final CompressionType compression;
        private final boolean autoCompress;
        private final List<Tlv> tlvs = new ArrayList<Tlv>();

        TlvContentBuilder(CompressionType compression, boolean autoCompress) {
            this.compression = compression;
            this.autoCompress = autoCompress;
        }

        public TlvContentBuilder add(Tlv tlv) { tlvs.add(tlv); return this; }

        public TlvContentBuilder addCaption(String text) { return add(Tlv.caption(text)); }
        public TlvContentBuilder addCaption(String text, int tagId) { return add(Tlv.caption(text).withTagId(tagId)); }
        public TlvContentBuilder addString(String text) { return add(Tlv.string(text)); }
        public TlvContentBuilder addString(String text, int tagId) { return add(Tlv.string(text).withTagId(tagId)); }
        public TlvContentBuilder addUnicodeString(String text) { return add(Tlv.unicodeString(text)); }
        public TlvContentBuilder addUnicodeString(String text, int tagId) { return add(Tlv.unicodeString(text).withTagId(tagId)); }
        public TlvContentBuilder addInteger(int value) { return add(Tlv.integer(value)); }
        public TlvContentBuilder addInteger(int value, int tagId) { return add(Tlv.integer(value).withTagId(tagId)); }
        public TlvContentBuilder addFloat(float value) { return add(Tlv.floatValue(value)); }
        public TlvContentBuilder addFloat(float value, int tagId) { return add(Tlv.floatValue(value).withTagId(tagId)); }
        public TlvContentBuilder addImage(byte[] imageValue) { return add(Tlv.image(imageValue)); }
        public TlvContentBuilder addImage(byte[] imageValue, int tagId) { return add(Tlv.image(imageValue).withTagId(tagId)); }
        public TlvContentBuilder addImage(ScImage image) { return add(Tlv.image(image)); }
        public TlvContentBuilder addImage(ScImage image, int tagId) { return add(Tlv.image(image).withTagId(tagId)); }
        public TlvContentBuilder addBiometric(byte[] template) { return add(Tlv.biometric(template)); }
        public TlvContentBuilder addBiometric(byte[] template, int tagId) { return add(Tlv.biometric(template).withTagId(tagId)); }

        public TableBuilder startTable(int rows, int cols) { return new TableBuilder(this, rows, cols, false); }
        public TableBuilder startTable(int rows, int cols, boolean headerRow) { return new TableBuilder(this, rows, cols, headerRow); }

        public ScDataWriter endContent() {
            addContent(new Content(compression == null ? ContentType.TLV : ContentType.COMPRESSED_TLV,
                    compression, tlvs, null, -1, autoCompress));
            return ScDataWriter.this;
        }
    }

    public class TableBuilder {
        private final TlvContentBuilder parent;
        private final Table table;
        private int tagId = -1;

        TableBuilder(TlvContentBuilder parent, int rows, int cols, boolean headerRow) {
            this.parent = parent;
            this.table = new Table(rows, cols, headerRow);
        }

        public TableBuilder withTagId(int tagId) {
            if (tagId < 0 || tagId > 0xFFFF) throw new ScDataException("Tag ID must be 0-65535");
            this.tagId = tagId;
            return this;
        }

        public TableBuilder setCell(int row, int col, TableCell cell) { table.setCell(row, col, cell); return this; }
        public TableBuilder setCaption(int row, int col, String text) { return setCell(row, col, TableCell.caption(text)); }
        public TableBuilder setString(int row, int col, String text) { return setCell(row, col, TableCell.string(text)); }
        public TableBuilder setUnicodeString(int row, int col, String text) { return setCell(row, col, TableCell.unicodeString(text)); }
        public TableBuilder setInteger(int row, int col, int value) { return setCell(row, col, TableCell.integer(value)); }
        public TableBuilder setFloat(int row, int col, float value) { return setCell(row, col, TableCell.floatValue(value)); }
        public TableBuilder setImage(int row, int col, byte[] imageValue) { return setCell(row, col, TableCell.image(imageValue)); }
        public TableBuilder setImage(int row, int col, ScImage image) { return setCell(row, col, TableCell.image(image)); }
        public TableBuilder setBiometric(int row, int col, byte[] template) { return setCell(row, col, TableCell.biometric(template)); }

        public TlvContentBuilder endTable() {
            Tlv t = Tlv.table(table);
            if (tagId >= 0) t = t.withTagId(tagId);
            return parent.add(t);
        }
    }

    public class VariablesBuilder {
        private final List<Variable> variables = new ArrayList<Variable>();

        public VariablesBuilder add(Variable v) { variables.add(v); return this; }

        public VariablesBuilder addString(int id, String text) { return add(Variable.string(id, text)); }
        public VariablesBuilder addUnicodeString(int id, String text) { return add(Variable.unicodeString(id, text)); }
        public VariablesBuilder addInteger(int id, int value) { return add(Variable.integer(id, value)); }
        public VariablesBuilder addFloat(int id, float value) { return add(Variable.floatValue(id, value)); }
        public VariablesBuilder addImage(int id, byte[] imageValue) { return add(Variable.image(id, imageValue)); }
        public VariablesBuilder addImage(int id, ScImage image) { return add(Variable.image(id, image)); }
        public VariablesBuilder addBiometric(int id, byte[] template) { return add(Variable.biometric(id, template)); }

        public ScDataWriter endContent() {
            addContent(new Content(ContentType.VARIABLES, null, null, variables));
            return ScDataWriter.this;
        }
    }
}

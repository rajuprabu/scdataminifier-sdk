package com.scdataminifier;

import java.util.ArrayList;
import java.util.List;

import com.scdataminifier.callback.DecryptionCallback;
import com.scdataminifier.callback.DictionaryProvider;
import com.scdataminifier.callback.SignatureVerifier;
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
import com.scdataminifier.util.ByteReader;
import com.scdataminifier.util.CompressionUtil;
import com.scdataminifier.util.Crc16;

/**
 * Parses SCDataMinifier payloads (format documented in SPEC.md).
 *
 * Encrypted payloads require a {@link DecryptionCallback}; the SDK never
 * holds keys. Signed payloads are verified through the optional
 * {@link SignatureVerifier}; when it is omitted the signature and signed
 * bytes remain available on the returned {@link ScData} for later
 * verification. Unsigned payloads are always CRC16-checked.
 */
public final class ScDataParser {

    public static final int SUPPORTED_VERSION = ScDataWriter.FORMAT_VERSION;

    private ScDataParser() {}

    /** Parses an unencrypted payload without signature verification. */
    public static ScData parse(byte[] payload) {
        return parse(payload, null, null, null);
    }

    public static ScData parse(byte[] payload, DecryptionCallback decryptor, SignatureVerifier verifier) {
        return parse(payload, decryptor, verifier, null);
    }

    public static ScData parse(byte[] payload, DecryptionCallback decryptor, SignatureVerifier verifier,
                               DictionaryProvider dictionaries) {
        ByteReader r = new ByteReader(payload);

        int magic = r.readByte();
        if (magic != ScDataWriter.MAGIC) {
            throw new ScDataException("Bad magic byte 0x" + Integer.toHexString(magic) + " (expected 0x03)");
        }
        int version = r.readByte();
        if (version > SUPPORTED_VERSION) {
            throw new ScDataException("Unsupported format version " + version + " (parser supports up to " + SUPPORTED_VERSION + ")");
        }
        int applicationId = r.readShort();
        long uniqueId = r.readLong5();
        int minClientVersion = r.readShort();
        int encByte = r.readByte();
        boolean encrypted = (encByte & 0x01) != 0;

        ScHeader header;
        byte[] plainRegion;
        if (encrypted) {
            EncryptionType encType = EncryptionType.fromCode((encByte >> 1) & 0x07);
            int keyVersion = r.readShort();
            int encLen = (encByte & 0x10) != 0 ? r.readShort() : r.readByte();
            byte[] encData = r.readBytes(encLen);
            if (r.hasMore()) throw new ScDataException(r.remaining() + " trailing byte(s) after encrypted data");
            header = new ScHeader(version, applicationId, uniqueId, minClientVersion, true, encType, keyVersion);
            if (decryptor == null) {
                throw new ScDataException("Payload is encrypted but no DecryptionCallback was provided");
            }
            plainRegion = decryptor.decrypt(header, encData);
            if (plainRegion == null) throw new ScDataException("DecryptionCallback returned null");
        } else {
            header = new ScHeader(version, applicationId, uniqueId, minClientVersion, false, null, 0);
            plainRegion = r.readBytes(r.remaining());
        }

        // plaintext region: content count + content blocks + signature block
        ByteReader pr = new ByteReader(plainRegion);
        int contentCount = pr.readByte();
        List<Content> contents = new ArrayList<Content>();
        for (int i = 0; i < contentCount; i++) {
            contents.add(readContent(pr, header, dictionaries));
        }
        int contentEnd = pr.position();

        byte[] signedData = new byte[11 + contentEnd];
        System.arraycopy(payload, 0, signedData, 0, 11);
        System.arraycopy(plainRegion, 0, signedData, 11, contentEnd);

        int sigByte = pr.readByte();
        SignatureAlgorithm algorithm = SignatureAlgorithm.fromCode(sigByte & 0x07);
        int sigKeyVersion = (sigByte >> 3) & 0x0F;
        byte[] signature = null;
        if (algorithm == SignatureAlgorithm.NONE) {
            int crc = pr.readShort();
            if (crc != Crc16.compute(signedData)) {
                throw new ScDataException("CRC16 mismatch - payload is corrupted");
            }
        } else {
            int sigLen = (sigByte & 0x80) != 0 ? pr.readShort() : pr.readByte();
            signature = pr.readBytes(sigLen);
            if (verifier != null && !verifier.verify(header, algorithm, sigKeyVersion, signedData, signature)) {
                throw new ScDataException("Digital signature verification failed");
            }
        }
        if (pr.hasMore()) throw new ScDataException(pr.remaining() + " trailing byte(s) after signature block");

        return new ScData(header, contents, algorithm, sigKeyVersion, signature, signedData);
    }

    // ==================== decoding internals ====================

    private static Content readContent(ByteReader r, ScHeader header, DictionaryProvider dictionaries) {
        int b = r.readByte();
        ContentType type = ContentType.fromCode(b & 0x0F);
        int len = (b & 0x10) != 0 ? r.readShort() : r.readByte();
        byte[] data = r.readBytes(len);
        switch (type) {
            case TLV:
                return new Content(ContentType.TLV, null, readTlvList(data), null);
            case COMPRESSED_TLV: {
                ByteReader d = new ByteReader(data);
                CompressionType compression = CompressionType.fromCode(d.readByte() & 0x0F);
                byte[] raw;
                int dictVersion = -1;
                if (compression == CompressionType.ZIP_DICT) {
                    dictVersion = d.readByte();
                    if (dictionaries == null) {
                        throw new ScDataException("Content uses ZIP_DICT but no DictionaryProvider was provided");
                    }
                    byte[] dict = dictionaries.getDictionary(header, dictVersion);
                    if (dict == null) {
                        throw new ScDataException("DictionaryProvider returned null for dictionary version " + dictVersion);
                    }
                    raw = CompressionUtil.decompress(d.readBytes(d.remaining()), compression, dict);
                } else {
                    raw = CompressionUtil.decompress(d.readBytes(d.remaining()), compression);
                }
                return new Content(ContentType.COMPRESSED_TLV, compression, readTlvList(raw), null, dictVersion, false);
            }
            case VARIABLES:
                return new Content(ContentType.VARIABLES, null, null, readVariables(data));
            default:
                throw new ScDataException("Unsupported content type: " + type);
        }
    }

    private static List<Tlv> readTlvList(byte[] data) {
        ByteReader r = new ByteReader(data);
        List<Tlv> tlvs = new ArrayList<Tlv>();
        while (r.hasMore()) {
            tlvs.add(readTlv(r));
        }
        return tlvs;
    }

    private static Tlv readTlv(ByteReader r) {
        int tag = r.readByte();
        TagType type = TagType.fromCode(tag & 0x0F);
        int len = (tag & 0x10) != 0 ? r.readShort() : r.readByte();
        int tagId = -1;
        if ((tag & 0x20) != 0) {
            tagId = (tag & 0x40) != 0 ? r.readShort() : r.readByte();
        }
        byte[] value = r.readBytes(len);
        if (type == TagType.TABLE) {
            Tlv t = Tlv.table(readTable(value));
            return tagId >= 0 ? t.withTagId(tagId) : t;
        }
        return new Tlv(type, tagId, value, null);
    }

    private static Table readTable(byte[] data) {
        ByteReader r = new ByteReader(data);
        int dims = r.readByte();
        int rows = ((dims >> 4) & 0x0F) + 1;
        int cols = (dims & 0x0F) + 1;
        int flags = r.readByte();
        Table table = new Table(rows, cols, (flags & 0x01) != 0);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int cb = r.readByte();
                if ((cb & 0x20) == 0) continue; // empty cell: no length, no value
                TagType cellType = TagType.fromCode(cb & 0x0F);
                if (cellType == TagType.TABLE) throw new ScDataException("Nested tables are not allowed");
                int len = (cb & 0x10) != 0 ? r.readShort() : r.readByte();
                table.setCell(row, col, new TableCell(cellType, r.readBytes(len)));
            }
        }
        if (r.hasMore()) throw new ScDataException(r.remaining() + " trailing byte(s) inside table value");
        return table;
    }

    private static List<Variable> readVariables(byte[] data) {
        ByteReader r = new ByteReader(data);
        List<Variable> vars = new ArrayList<Variable>();
        while (r.hasMore()) {
            int b = r.readByte();
            TagType type = TagType.fromCode(b & 0x0F);
            int id = r.readByte() & 0x1F;
            int len = (b & 0x10) != 0 ? r.readShort() : r.readByte();
            vars.add(new Variable(type, id, r.readBytes(len)));
        }
        return vars;
    }
}

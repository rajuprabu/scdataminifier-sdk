package com.scdataminifier.model;

import java.util.Collections;
import java.util.List;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.CompressionType;
import com.scdataminifier.enums.ContentType;

/** One content block: a TLV list (optionally compressed) or a variable set. */
public class Content {

    private final ContentType type;
    private final CompressionType compression; // non-null only for COMPRESSED_TLV
    private final List<Tlv> tlvs;              // TLV and COMPRESSED_TLV
    private final List<Variable> variables;    // VARIABLES
    private final int dictionaryVersion;       // >=0 only for parsed ZIP_DICT contents
    private final boolean autoCompress;        // writer-side: fall back to plain TLV if compression does not help

    public Content(ContentType type, CompressionType compression, List<Tlv> tlvs, List<Variable> variables) {
        this(type, compression, tlvs, variables, -1, false);
    }

    public Content(ContentType type, CompressionType compression, List<Tlv> tlvs, List<Variable> variables,
                   int dictionaryVersion, boolean autoCompress) {
        if (type == null) throw new ScDataException("Content type is null");
        if (type == ContentType.COMPRESSED_TLV && compression == null) {
            throw new ScDataException("COMPRESSED_TLV content requires a compression type");
        }
        if (type == ContentType.VARIABLES) {
            if (variables == null) throw new ScDataException("VARIABLES content requires variables");
        } else {
            if (tlvs == null) throw new ScDataException(type + " content requires tlvs");
        }
        this.type = type;
        this.compression = type == ContentType.COMPRESSED_TLV ? compression : null;
        this.tlvs = tlvs;
        this.variables = variables;
        this.dictionaryVersion = dictionaryVersion;
        this.autoCompress = autoCompress;
    }

    public ContentType getType() { return type; }

    /** Compression used, or null when not COMPRESSED_TLV. */
    public CompressionType getCompression() { return compression; }

    /** Dictionary version of a parsed ZIP_DICT content, or -1. */
    public int getDictionaryVersion() { return dictionaryVersion; }

    /** Writer-side hint: emit as plain TLV when compression does not save space. */
    public boolean isAutoCompress() { return autoCompress; }

    public List<Tlv> getTlvs() {
        if (type == ContentType.VARIABLES) throw new ScDataException("VARIABLES content has no tlvs");
        return Collections.unmodifiableList(tlvs);
    }

    public List<Variable> getVariables() {
        if (type != ContentType.VARIABLES) throw new ScDataException(type + " content has no variables");
        return Collections.unmodifiableList(variables);
    }
}

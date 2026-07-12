package com.scdataminifier.model;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.TagType;
import com.scdataminifier.util.ValueCodec;

/**
 * One Tag-Length-Value entry. Immutable; create through the static factories,
 * attach an optional identifier with {@link #withTagId(int)}.
 */
public class Tlv {

    private final TagType type;
    private final int tagId;     // -1 = no tag ID
    private final byte[] value;  // null only for TABLE
    private final Table table;   // non-null only for TABLE

    public Tlv(TagType type, int tagId, byte[] value, Table table) {
        if (type == null) throw new ScDataException("Tag type is null");
        if (type == TagType.TABLE) {
            if (table == null) throw new ScDataException("TABLE tlv requires a Table");
        } else {
            if (value == null) throw new ScDataException(type + " tlv requires a value");
            if (value.length > 0xFFFF) {
                throw new ScDataException(type + " tlv value is " + value.length + " bytes, exceeding the 65535-byte limit");
            }
        }
        if (tagId < -1 || tagId > 0xFFFF) throw new ScDataException("Tag ID must be 0-65535");
        this.type = type;
        this.tagId = tagId;
        this.value = value;
        this.table = table;
    }

    // ==================== factories ====================

    public static Tlv caption(String text) { return new Tlv(TagType.CAPTION, -1, ValueCodec.fromUtf8(text), null); }

    public static Tlv string(String text) { return new Tlv(TagType.STRING, -1, ValueCodec.fromLatin1(text), null); }

    public static Tlv unicodeString(String text) { return new Tlv(TagType.UNICODE_STRING, -1, ValueCodec.fromUtf8(text), null); }

    public static Tlv integer(int v) { return new Tlv(TagType.INTEGER, -1, ValueCodec.fromInt(v), null); }

    public static Tlv floatValue(float v) { return new Tlv(TagType.FLOAT, -1, ValueCodec.fromFloat(v), null); }

    /** Raw pre-encoded IMAGE value (image header byte + data); prefer {@link #image(ScImage)}. */
    public static Tlv image(byte[] imageValue) { return new Tlv(TagType.IMAGE, -1, imageValue, null); }

    public static Tlv image(ScImage image) { return new Tlv(TagType.IMAGE, -1, image.encode(), null); }

    public static Tlv biometric(byte[] template) { return new Tlv(TagType.BIOMETRIC, -1, template, null); }

    public static Tlv table(Table t) { return new Tlv(TagType.TABLE, -1, null, t); }

    /** Returns a copy of this tlv linked to the given identifier (0-65535). */
    public Tlv withTagId(int tagId) {
        if (tagId < 0 || tagId > 0xFFFF) throw new ScDataException("Tag ID must be 0-65535");
        return new Tlv(type, tagId, value, table);
    }

    // ==================== accessors ====================

    public TagType getType() { return type; }

    public boolean hasTagId() { return tagId >= 0; }

    public int getTagId() { return tagId; }

    /** Raw value bytes (not valid for TABLE). */
    public byte[] getValue() {
        if (type == TagType.TABLE) throw new ScDataException("TABLE tlv has no raw value; use asTable()");
        return value;
    }

    public Table asTable() {
        if (type != TagType.TABLE) throw new ScDataException(type + " tlv is not a table");
        return table;
    }

    public String asString() {
        switch (type) {
            case CAPTION:
            case UNICODE_STRING: return ValueCodec.toUtf8(value);
            case STRING:         return ValueCodec.toLatin1(value);
            default: throw new ScDataException(type + " tlv is not a text value");
        }
    }

    public int asInt() {
        if (type != TagType.INTEGER) throw new ScDataException(type + " tlv is not an INTEGER");
        return ValueCodec.toInt(value);
    }

    public float asFloat() {
        if (type != TagType.FLOAT) throw new ScDataException(type + " tlv is not a FLOAT");
        return ValueCodec.toFloat(value);
    }

    public ScImage asImage() {
        if (type != TagType.IMAGE) throw new ScDataException(type + " tlv is not an IMAGE");
        return ScImage.parse(value);
    }
}

package com.scdataminifier.model;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.TagType;
import com.scdataminifier.util.ValueCodec;

/** One populated table cell. An empty cell is represented by null in the Table grid. */
public class TableCell {

    private final TagType type;
    private final byte[] value;

    public TableCell(TagType type, byte[] value) {
        if (type == null || value == null) throw new ScDataException("Cell type/value is null");
        if (type == TagType.TABLE) throw new ScDataException("Nested tables are not allowed");
        if (value.length > 0xFFFF) {
            throw new ScDataException(type + " cell value is " + value.length + " bytes, exceeding the 65535-byte limit");
        }
        this.type = type;
        this.value = value;
    }

    public static TableCell caption(String text) { return new TableCell(TagType.CAPTION, ValueCodec.fromUtf8(text)); }

    public static TableCell string(String text) { return new TableCell(TagType.STRING, ValueCodec.fromLatin1(text)); }

    public static TableCell unicodeString(String text) { return new TableCell(TagType.UNICODE_STRING, ValueCodec.fromUtf8(text)); }

    public static TableCell integer(int v) { return new TableCell(TagType.INTEGER, ValueCodec.fromInt(v)); }

    public static TableCell floatValue(float v) { return new TableCell(TagType.FLOAT, ValueCodec.fromFloat(v)); }

    /** Raw pre-encoded IMAGE value (image header byte + data); prefer {@link #image(ScImage)}. */
    public static TableCell image(byte[] imageValue) { return new TableCell(TagType.IMAGE, imageValue); }

    public static TableCell image(ScImage image) { return new TableCell(TagType.IMAGE, image.encode()); }

    public static TableCell biometric(byte[] template) { return new TableCell(TagType.BIOMETRIC, template); }

    public TagType getType() { return type; }

    public byte[] getValue() { return value; }

    public String asString() {
        switch (type) {
            case CAPTION:
            case UNICODE_STRING: return ValueCodec.toUtf8(value);
            case STRING:         return ValueCodec.toLatin1(value);
            default: throw new ScDataException(type + " cell is not a text value");
        }
    }

    public int asInt() {
        if (type != TagType.INTEGER) throw new ScDataException(type + " cell is not an INTEGER");
        return ValueCodec.toInt(value);
    }

    public float asFloat() {
        if (type != TagType.FLOAT) throw new ScDataException(type + " cell is not a FLOAT");
        return ValueCodec.toFloat(value);
    }

    public ScImage asImage() {
        if (type != TagType.IMAGE) throw new ScDataException(type + " cell is not an IMAGE");
        return ScImage.parse(value);
    }
}

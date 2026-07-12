package com.scdataminifier.model;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.TagType;
import com.scdataminifier.util.ValueCodec;

/**
 * A named-by-number variable (ID 0-31). Variables are referenced from
 * outside the payload - e.g. by the HTML Minifier or directly by the
 * rendering application.
 */
public class Variable {

    public static final int MAX_ID = 31; // 5-bit variable ID

    private final TagType type;
    private final int id;
    private final byte[] value;

    public Variable(TagType type, int id, byte[] value) {
        if (type == null || value == null) throw new ScDataException("Variable type/value is null");
        if (type == TagType.TABLE) throw new ScDataException("A variable cannot hold a table");
        if (id < 0 || id > MAX_ID) throw new ScDataException("Variable ID must be 0-" + MAX_ID);
        if (value.length > 0xFFFF) {
            throw new ScDataException(type + " variable value is " + value.length + " bytes, exceeding the 65535-byte limit");
        }
        this.type = type;
        this.id = id;
        this.value = value;
    }

    public static Variable string(int id, String text) { return new Variable(TagType.STRING, id, ValueCodec.fromLatin1(text)); }

    public static Variable unicodeString(int id, String text) { return new Variable(TagType.UNICODE_STRING, id, ValueCodec.fromUtf8(text)); }

    public static Variable integer(int id, int v) { return new Variable(TagType.INTEGER, id, ValueCodec.fromInt(v)); }

    public static Variable floatValue(int id, float v) { return new Variable(TagType.FLOAT, id, ValueCodec.fromFloat(v)); }

    /** Raw pre-encoded IMAGE value (image header byte + data); prefer {@link #image(int, ScImage)}. */
    public static Variable image(int id, byte[] imageValue) { return new Variable(TagType.IMAGE, id, imageValue); }

    public static Variable image(int id, ScImage image) { return new Variable(TagType.IMAGE, id, image.encode()); }

    public static Variable biometric(int id, byte[] template) { return new Variable(TagType.BIOMETRIC, id, template); }

    public TagType getType() { return type; }

    public int getId() { return id; }

    public byte[] getValue() { return value; }

    public String asString() {
        switch (type) {
            case CAPTION:
            case UNICODE_STRING: return ValueCodec.toUtf8(value);
            case STRING:         return ValueCodec.toLatin1(value);
            default: throw new ScDataException(type + " variable is not a text value");
        }
    }

    public int asInt() {
        if (type != TagType.INTEGER) throw new ScDataException(type + " variable is not an INTEGER");
        return ValueCodec.toInt(value);
    }

    public float asFloat() {
        if (type != TagType.FLOAT) throw new ScDataException(type + " variable is not a FLOAT");
        return ValueCodec.toFloat(value);
    }

    public ScImage asImage() {
        if (type != TagType.IMAGE) throw new ScDataException(type + " variable is not an IMAGE");
        return ScImage.parse(value);
    }
}

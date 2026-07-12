package com.scdataminifier;

/** Thrown for any build or parse error in the SCDataMinifier SDK. */
public class ScDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScDataException(String message) { super(message); }

    public ScDataException(String message, Throwable cause) { super(message, cause); }
}

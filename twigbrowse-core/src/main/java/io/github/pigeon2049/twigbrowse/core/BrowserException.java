package io.github.pigeon2049.twigbrowse.core;

public final class BrowserException extends RuntimeException {
    private final String code;
    public BrowserException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}

package com.tesa.foodbridge.shared;

/** HTTP-independent error. The web adapter maps code() to its documented response. */
public class RuleViolation extends RuntimeException {
    private final String code;
    public RuleViolation(String code) { super(code); this.code = code; }
    public String code() { return code; }
}

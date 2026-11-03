package com.tesa.foodbridge.shared;

/** Raised only after an authorized command has closed an overdue aggregate. */
public final class DeadlineExpired extends RuleViolation {
    public DeadlineExpired() { super("LISTING_EXPIRED"); }
}

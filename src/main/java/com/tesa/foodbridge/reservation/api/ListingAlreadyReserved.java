package com.tesa.foodbridge.reservation.api;
import com.tesa.foodbridge.shared.RuleViolation;
public final class ListingAlreadyReserved extends RuleViolation {
    public ListingAlreadyReserved() { super("LISTING_ALREADY_RESERVED"); }
}

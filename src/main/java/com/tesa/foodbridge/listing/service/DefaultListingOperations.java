
package com.tesa.foodbridge.listing.service;

import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.listing.repository.ListingRepository;
import com.tesa.foodbridge.shared.*;
import com.tesa.foodbridge.user.api.UserDirectory;
import com.tesa.foodbridge.activity.api.ActivityRecorder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DefaultListingOperations implements ListingOperations {

    private final ListingRepository repository;
    private final UserDirectory users;
    private final ActivityRecorder activity;
    private final com.tesa.foodbridge.expiration.api.ExpirationOperations expiration;

    public DefaultListingOperations(
            ListingRepository repository,
            UserDirectory users,
            ActivityRecorder activity,
            com.tesa.foodbridge.expiration.api.ExpirationOperations expiration) {
        this.repository = repository;
        this.users = users;
        this.activity = activity;
        this.expiration = expiration;
    }

    @Transactional
    public ListingView create(Actor actor, CreateListing c) {

        if (actor.role() != Actor.Role.DONOR)
            throw new RuleViolation("FORBIDDEN");

        users.requireRole(actor.id(), actor.role());
        validate(c);

        var listing = repository.insert(
                UUID.randomUUID(), actor.id(), c
        );

        activity.record(actor.id(), listing.id(), "LISTING_CREATED");

        return listing;
    }

    @Transactional(noRollbackFor=DeadlineExpired.class)
    public ListingView update(Actor actor, UUID id, CreateListing c) {

        if (actor.role() != Actor.Role.DONOR)
            throw new RuleViolation("FORBIDDEN");

        users.requireRole(actor.id(), actor.role());
        validate(c);

        ListingView existing = lock(id);

        if (!existing.donorId().equals(actor.id()))
            throw new RuleViolation("FORBIDDEN");

        expireAuthorized(id);

        if (!existing.status().equals("AVAILABLE"))
            throw new RuleViolation("LISTING_NOT_EDITABLE");

        return repository.update(id, c);
    }

    private void validate(CreateListing c) {

        if (c == null
                || blank(c.title(), 300)
                || blank(c.description(), Integer.MAX_VALUE)
                || blank(c.pickupArea(), 200)
                || c.quantity() == null
                || c.quantity().signum() <= 0
                || c.quantity().scale() > 2
                || c.quantity().precision() - c.quantity().scale() > 10
                || c.category() == null
                || !Set.of("PREPARED_MEALS", "PRODUCE", "BAKERY",
                "DAIRY", "PANTRY", "OTHER").contains(c.category())
                || c.unit() == null
                || !Set.of("PORTIONS", "KG", "LITRES", "ITEMS",
                "BOXES").contains(c.unit())
                || c.collectionDeadline() == null
                || !c.collectionDeadline().isAfter(Instant.now())) {

            throw new RuleViolation("INVALID_LISTING");
        }
    }

    private boolean blank(String s, int max) {
        return s == null || s.isBlank() || s.length() > max;
    }

    public List<ListingView> browse(int offset, int limit) {
        validatePage(offset, limit);
        return repository.browse(offset, limit);
    }

    public List<ListingView> browseMine(
            Actor actor, int offset, int limit) {

        if (actor.role() != Actor.Role.DONOR
                && actor.role() != Actor.Role.ADMIN)
            throw new RuleViolation("FORBIDDEN");

        users.requireRole(actor.id(), actor.role());
        validatePage(offset, limit);

        return repository.browseMine(actor.id(), offset, limit);
    }

    private void validatePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100)
            throw new RuleViolation("INVALID_PAGE");
    }

    private ListingView lock(UUID id) {
        return repository.findLocked(id).stream()
                .findFirst()
                .orElseThrow(() -> new RuleViolation("LISTING_NOT_FOUND"));
    }

    @Override
    @Transactional(noRollbackFor=DeadlineExpired.class)
    public void delete(Actor actor, UUID id) {
        users.requireRole(actor.id(), actor.role());
        var listing = lock(id);
        if (actor.role() != Actor.Role.ADMIN
                && (actor.role() != Actor.Role.DONOR || !actor.id().equals(listing.donorId()))) {
            throw new RuleViolation("FORBIDDEN");
        }
        // Authorization precedes the no-op, including for already deleted rows.
        if ("DELETED".equals(listing.status())) return;
        expireAuthorized(id);
        if (!"AVAILABLE".equals(listing.status())) throw new RuleViolation("LISTING_NOT_DELETABLE");
        activity.record(actor.id(), id, "LISTING_ARCHIVED", repository.archiveDetails(id));
        if (repository.markDeleted(id) != 1) throw new RuleViolation("LISTING_NOT_DELETABLE");
    }

    public ListingView get(UUID id) {
        return repository.find(id).stream()
                .findFirst()
                .orElseThrow(() -> new RuleViolation("LISTING_NOT_FOUND"));
    }

    private void requireOpen(ListingView listing) {

        if (listing.status().equals("DELETED")) throw new RuleViolation("LISTING_NOT_FOUND");

        if (listing.status().equals("EXPIRED"))
            throw new RuleViolation("LISTING_EXPIRED");

        if (!listing.status().equals("AVAILABLE"))
            throw new RuleViolation("LISTING_ALREADY_RESERVED");
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor=DeadlineExpired.class)
    public ListingView lockForRequest(UUID id) {
        var l = lock(id);
        expireAuthorized(id);
        requireOpen(l);
        return l;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor=DeadlineExpired.class)
    public ListingView lockForApproval(UUID id, Actor actor) {

        var l = lock(id);

        if (actor.role() != Actor.Role.ADMIN
                && (actor.role() != Actor.Role.DONOR
                || !actor.id().equals(l.donorId())))
            throw new RuleViolation("FORBIDDEN");

        users.requireRole(actor.id(), actor.role());
        expireAuthorized(id);
        requireOpen(l);

        return l;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markReserved(UUID id) {
        repository.reserve(id);
    }

    public ListingView getPublic(UUID id) {
        var listing=get(id);
        if (listing.status().equals("DELETED")) throw new RuleViolation("LISTING_NOT_FOUND");
        return listing;
    }

    public ListingView getHistorical(Actor actor,UUID id) {
        var listing=get(id);
        if (!listing.status().equals("DELETED")) return listing;
        users.requireRole(actor.id(),actor.role());
        if (actor.role()==Actor.Role.ADMIN
                || (actor.role()==Actor.Role.DONOR && actor.id().equals(listing.donorId()))
                || (actor.role()==Actor.Role.RECIPIENT && repository.hasRequest(id,actor.id()))) return listing;
        throw new RuleViolation("FORBIDDEN");
    }

    private void expireAuthorized(UUID id) {
        if (expiration.expireLocked(id)) throw new DeadlineExpired();
    }
    public void expireListings() { expiration.expireDue(); }
}

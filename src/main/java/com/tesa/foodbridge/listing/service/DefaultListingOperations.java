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

    public DefaultListingOperations(
            ListingRepository repository,
            UserDirectory users,
            ActivityRecorder activity) {

        this.repository = repository;
        this.users = users;
        this.activity = activity;
    }

    @Transactional
    public ListingView create(
            Actor actor,
            CreateListing c) {

        if (actor.role() != Actor.Role.DONOR)
            throw new RuleViolation("FORBIDDEN");

        users.requireRole(actor.id(), actor.role());

        if (c == null
                || blank(c.title(), 300)
                || blank(c.description(), Integer.MAX_VALUE)
                || blank(c.pickupArea(), 200)
                || c.quantity() == null
                || c.quantity().signum() <= 0
                || c.quantity().scale() > 2
                || c.quantity().precision()
                - c.quantity().scale() > 10
                || c.category() == null
                || !Set.of(
                "PREPARED_MEALS",
                "PRODUCE",
                "BAKERY",
                "DAIRY",
                "PANTRY",
                "OTHER"
        ).contains(c.category())
                || c.unit() == null
                || !Set.of(
                "PORTIONS",
                "KG",
                "LITRES",
                "ITEMS",
                "BOXES"
        ).contains(c.unit())
                || c.collectionDeadline() == null
                || !c.collectionDeadline()
                .isAfter(Instant.now())) {

            throw new RuleViolation("INVALID_LISTING");
        }

        var listing =
                repository.insert(
                        UUID.randomUUID(),
                        actor.id(),
                        c
                );

        activity.record(
                actor.id(),
                listing.id(),
                "LISTING_CREATED"
        );

        return listing;
    }

    private boolean blank(
            String s,
            int max) {

        return s == null
                || s.isBlank()
                || s.length() > max;
    }

    public List<ListingView> browse(
            int offset,
            int limit) {

        if (offset < 0
                || limit < 1
                || limit > 100) {

            throw new RuleViolation("INVALID_PAGE");
        }

        return repository.browse(
                offset,
                limit
        );
    }

    public List<ListingView> browseMine(
            Actor actor,
            int offset,
            int limit) {

        if (actor.role() != Actor.Role.DONOR
                && actor.role() != Actor.Role.ADMIN) {

            throw new RuleViolation("FORBIDDEN");
        }

        users.requireRole(
                actor.id(),
                actor.role()
        );

        if (offset < 0
                || limit < 1
                || limit > 100) {

            throw new RuleViolation("INVALID_PAGE");
        }

        return repository.browseMine(
                actor.id(),
                offset,
                limit
        );
    }

    private ListingView lock(UUID id) {

        return repository
                .findLocked(id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new RuleViolation(
                                "LISTING_NOT_FOUND"
                        )
                );
    }

    public ListingView get(UUID id) {

        return repository
                .find(id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new RuleViolation(
                                "LISTING_NOT_FOUND"
                        )
                );
    }

    private void requireOpen(
            ListingView listing) {

        if (repository.deadlinePassed(listing.id())
                || listing.status().equals("EXPIRED")) {

            throw new RuleViolation(
                    "LISTING_EXPIRED"
            );
        }

        if (!listing.status().equals("AVAILABLE")) {

            throw new RuleViolation(
                    "LISTING_ALREADY_RESERVED"
            );
        }
    }

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public ListingView lockForRequest(
            UUID id) {

        var l = lock(id);

        requireOpen(l);

        return l;
    }

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public ListingView lockForApproval(
            UUID id,
            Actor actor) {

        var l = lock(id);

        if (actor.role() != Actor.Role.ADMIN
                && (actor.role() != Actor.Role.DONOR
                || !actor.id().equals(l.donorId()))) {

            throw new RuleViolation(
                    "FORBIDDEN"
            );
        }

        users.requireRole(
                actor.id(),
                actor.role()
        );

        requireOpen(l);

        return l;
    }

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void markReserved(UUID id) {

        repository.reserve(id);
    }
}
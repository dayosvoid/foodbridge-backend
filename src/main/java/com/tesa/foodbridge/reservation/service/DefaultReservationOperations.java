package com.tesa.foodbridge.reservation.service;

import com.tesa.foodbridge.reservation.api.*;
import com.tesa.foodbridge.reservation.repository.RequestRepository;
import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.handover.api.HandoverOperations;
import com.tesa.foodbridge.activity.api.ActivityRecorder;
import com.tesa.foodbridge.user.api.UserDirectory;
import com.tesa.foodbridge.shared.*;
import com.tesa.foodbridge.distribution.api.DailyDistributionOperations;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.UUID;

@Service
public class DefaultReservationOperations
        implements ReservationOperations {

    private final RequestRepository requests;
    private final ListingOperations listings;
    private final HandoverOperations handovers;
    private final ActivityRecorder activity;
    private final UserDirectory users;
    private final DailyDistributionOperations dailyDistribution;
    private final TransactionTemplate tx;
    private final com.tesa.foodbridge.expiration.api.ExpirationOperations expiration;

    public DefaultReservationOperations(
            RequestRepository requests,
            ListingOperations listings,
            HandoverOperations handovers,
            ActivityRecorder activity,
            UserDirectory users,
            DailyDistributionOperations dailyDistribution,
            PlatformTransactionManager manager,
            com.tesa.foodbridge.expiration.api.ExpirationOperations expiration) {

        this.requests = requests;
        this.listings = listings;
        this.handovers = handovers;
        this.activity = activity;
        this.users = users;
        this.expiration = expiration;
        this.dailyDistribution = dailyDistribution;

        tx = new TransactionTemplate(manager);

        tx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );

        tx.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
    }

    public RequestView request(
            Actor actor,
            UUID listingId,
            String key) {

        if (actor.role() != Actor.Role.RECIPIENT)
            throw new RuleViolation("FORBIDDEN");

        users.requireRole(
                actor.id(),
                actor.role()
        );

        if (key != null
                && (key.isBlank()
                || key.length() > 200)) {

            throw new RuleViolation(
                    "INVALID_IDEMPOTENCY_KEY"
            );
        }

        try {

            return execute(s -> {

                if (key != null) {

                    var old = requests.byKey(key);

                    if (old.isPresent()) {

                        return replay(
                                old.get(),
                                actor,
                                listingId
                        );
                    }
                }

                listings.lockForRequest(listingId);

                var result = requests.insert(
                        UUID.randomUUID(),
                        listingId,
                        actor.id(),
                        key
                );

                activity.record(
                        actor.id(),
                        listingId,
                        "REQUEST_CREATED"
                );

                return result;
            });

        } catch (DataIntegrityViolationException e) {

            if (key != null
                    && unique(
                    e,
                    "requests_idempotency_key_key"
            )) {

                return execute(
                        s -> replay(
                                requests
                                        .byKey(key)
                                        .orElseThrow(() -> e),
                                actor,
                                listingId
                        )
                );
            }

            throw e;
        }
    }

    private RequestView replay(
            RequestView old,
            Actor actor,
            UUID listing) {

        if (!old.requesterId().equals(actor.id())
                || !old.listingId().equals(listing)) {

            throw new RuleViolation(
                    "IDEMPOTENCY_KEY_REUSED"
            );
        }

        return old;
    }

    public RequestView decline(
            Actor actor,
            UUID id) {

        return execute(s -> {

            var request = required(id);

            var listing = listings.get(
                    request.listingId()
            );

            if (actor.role() != Actor.Role.ADMIN
                    && (actor.role() != Actor.Role.DONOR
                    || !actor.id().equals(listing.donorId()))) {

                throw new RuleViolation("FORBIDDEN");
            }

            users.requireRole(
                    actor.id(),
                    actor.role()
            );

            lockAndExpire(request.listingId());

            if (!request.status()
                    .equals("PENDING")
                    || !requests.decline(id)) {

                throw new RuleViolation(
                        "REQUEST_NOT_PENDING"
                );
            }

            activity.record(
                    actor.id(),
                    request.listingId(),
                    "REQUEST_DECLINED"
            );

            return required(id);
        });
    }

    public RequestView cancel(
            Actor actor,
            UUID id) {

        if (actor.role() != Actor.Role.RECIPIENT) {
            throw new RuleViolation("FORBIDDEN");
        }

        users.requireRole(
                actor.id(),
                actor.role()
        );

        return execute(s -> {

            var request = required(id);

            if (!request.requesterId().equals(actor.id())) {
                throw new RuleViolation("FORBIDDEN");
            }

            lockAndExpire(request.listingId());

            if (!request.status()
                    .equals("PENDING")
                    || !requests.cancel(
                    id,
                    actor.id()
            )) {

                throw new RuleViolation(
                        "REQUEST_NOT_PENDING"
                );
            }

            activity.record(
                    actor.id(),
                    request.listingId(),
                    "REQUEST_CANCELLED"
            );

            return required(id);
        });
    }

    public java.util.List<RequestView> forListing(
            Actor actor,
            UUID listingId) {

        var listing = listings.get(listingId);

        if (actor.role() != Actor.Role.ADMIN
                && (actor.role() != Actor.Role.DONOR
                || !actor.id().equals(listing.donorId()))) {

            throw new RuleViolation("FORBIDDEN");
        }

        users.requireRole(
                actor.id(),
                actor.role()
        );

        return requests.findForListing(listingId);
    }

    public java.util.List<RequestView> mine(
            Actor actor) {

        if (actor.role() != Actor.Role.RECIPIENT) {
            throw new RuleViolation("FORBIDDEN");
        }

        users.requireRole(
                actor.id(),
                actor.role()
        );

        return requests.findMine(actor.id());
    }

    public RequestPage browseAll(
            Actor actor,
            String status,
            int offset,
            int limit) {

        if (actor.role() != Actor.Role.ADMIN) {
            throw new RuleViolation("FORBIDDEN");
        }

        users.requireRole(
                actor.id(),
                actor.role()
        );

        if (offset < 0 || limit < 1 || limit > 100) {
            throw new RuleViolation("INVALID_PAGE");
        }

        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("PENDING", "APPROVED", "DECLINED", "CANCELLED", "EXPIRED", "REVERSED").contains(normalized)) {
                throw new RuleViolation("INVALID_REQUEST");
            }
        }

        return requests.browseAll(status, offset, limit);
    }

    public RequestView approve(
            Actor actor,
            UUID id) {

        try {

            return execute(s -> {

                var request = required(id);

                listings.lockForApproval(
                        request.listingId(),
                        actor
                );

                if (!request.status().equals("PENDING")) {
                    throw new RuleViolation(
                            "REQUEST_NOT_PENDING"
                    );
                }

                // Apply the daily fairness rule before approval.
                dailyDistribution.assertRecipientEligibleForApproval(
                        request.requesterId()
                );

                if (!requests.approve(id)) {
                    throw new RuleViolation(
                            "REQUEST_NOT_PENDING"
                    );
                }

                requests.declineOtherPendingForListing(
                        request.listingId(),
                        id
                );

                handovers.createForApprovedRequest(id);

                listings.markReserved(
                        request.listingId()
                );

                activity.record(
                        actor.id(),
                        request.listingId(),
                        "REQUEST_APPROVED"
                );

                return required(id);
            });

        } catch (DataIntegrityViolationException e) {

            if (unique(
                    e,
                    "one_approved_request_per_listing"
            )) {

                throw new ListingAlreadyReserved();
            }

            throw e;

        } catch (RuleViolation e) {

            if (e.code().equals("LISTING_ALREADY_RESERVED")) {
                throw new ListingAlreadyReserved();
            }

            throw e;
        }
    }

    private RequestView required(UUID id) {

        return requests.find(id)
                .orElseThrow(
                        () -> new RuleViolation(
                                "REQUEST_NOT_FOUND"
                        )
                );
    }

    public RequestView get(
            Actor actor,
            UUID id) {

        users.requireRole(
                actor.id(),
                actor.role()
        );

        var r = required(id);

        if (actor.role() != Actor.Role.ADMIN
                && !actor.id().equals(r.requesterId())
                && !(actor.role() == Actor.Role.DONOR
                && listings.get(r.listingId())
                .donorId()
                .equals(actor.id()))) {

            throw new RuleViolation("FORBIDDEN");
        }

        return r;
    }

    // A deadline conflict is raised only after its successful expiration transaction commits.
    private <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
        var expired = new boolean[1];
        T result=tx.execute(status->{
            try { return action.doInTransaction(status); }
            catch (DeadlineExpired e) { expired[0]=true; return null; }
        });
        if(expired[0]) throw new DeadlineExpired();
        return result;
    }

    private void lockAndExpire(UUID listingId) {
        if(expiration.expireLocked(listingId)) throw new DeadlineExpired();
    }

    private boolean unique(
            Throwable e,
            String constraint) {

        for (Throwable c = e; c != null; c = c.getCause()) {

            if (c instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(constraint)) {

                return true;
            }
        }

        return false;
    }
}

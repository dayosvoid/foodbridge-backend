package com.tesa.foodbridge.handover.service;

import com.tesa.foodbridge.activity.api.ActivityRecorder;
import com.tesa.foodbridge.handover.api.HandoverOperations;
import com.tesa.foodbridge.shared.RuleViolation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class DatabaseHandoverOperations implements HandoverOperations {

    private final JdbcTemplate jdbc;
    private final ActivityRecorder activity;

    DatabaseHandoverOperations(
            JdbcTemplate jdbc,
            ActivityRecorder activity) {
        this.jdbc = jdbc;
        this.activity = activity;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createForApprovedRequest(UUID requestId) {
        jdbc.update("""
            insert into handovers(id, request_id, status)
            values (?, ?, 'AWAITING_COLLECTION')
            """, UUID.randomUUID(), requestId);
    }

    @Override
    @Transactional
    public void completeByDonorOrAdmin(
            UUID requestId,
            UUID actorId,
            boolean admin) {

        UUID listingId;

        try {
            listingId = jdbc.queryForObject("""
                select l.id
                from handovers h
                join requests r on r.id = h.request_id
                join listings l on l.id = r.listing_id
                where r.id = ?
                  and r.status = 'APPROVED'
                  and h.status = 'AWAITING_COLLECTION'
                  and (? = true or l.donor_id = ?)
                for update of h, r, l
                """, UUID.class, requestId, admin, actorId);

        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new RuleViolation("HANDOVER_NOT_FOUND_OR_FORBIDDEN");
        }

        int updated = jdbc.update("""
            update handovers
            set status = 'COLLECTED',
                completed_at = clock_timestamp()
            where request_id = ?
              and status = 'AWAITING_COLLECTION'
            """, requestId);

        if (updated != 1) {
            throw new RuleViolation("HANDOVER_NOT_PENDING");
        }

        int listingUpdated = jdbc.update("""
            update listings
            set status = 'COLLECTED',
                updated_at = clock_timestamp()
            where id = ?
              and status = 'RESERVED'
            """, listingId);

        if (listingUpdated != 1) {
            throw new RuleViolation("LISTING_NOT_RESERVED");
        }

        activity.record(actorId, listingId, "COLLECTED");
    }

    @Override
    @Transactional
    public void reverseByDonorOrAdmin(
            UUID handoverId,
            UUID actorId,
            boolean admin) {

        UUID listingId;

        try {
            listingId = jdbc.queryForObject("""
                select l.id
                from handovers h
                join requests r on r.id = h.request_id
                join listings l on l.id = r.listing_id
                where h.id = ?
                  and h.status = 'COLLECTED'
                  and l.status = 'COLLECTED'
                  and (? = true or l.donor_id = ?)
                for update of h, l
                """, UUID.class, handoverId, admin, actorId);

        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new RuleViolation("HANDOVER_NOT_FOUND_OR_FORBIDDEN");
        }

        int handoverUpdated = jdbc.update("""
            update handovers
            set status = 'REVERSED',
                reversed_at = clock_timestamp()
            where id = ?
              and status = 'COLLECTED'
            """, handoverId);

        if (handoverUpdated != 1) {
            throw new RuleViolation("HANDOVER_NOT_COLLECTED");
        }

        int listingUpdated = jdbc.update("""
            update listings
            set status = 'REVERSED',
                updated_at = clock_timestamp()
            where id = ?
              and status = 'COLLECTED'
            """, listingId);

        if (listingUpdated != 1) {
            throw new RuleViolation("LISTING_NOT_COLLECTED");
        }

        activity.record(actorId, listingId, "REVERSED");
    }
}
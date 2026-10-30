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
    public void completeByRecipient(
            UUID requestId,
            UUID recipientId) {

        Boolean ownsRequest = jdbc.queryForObject("""
            select exists (
                select 1
                from requests
                where id = ?
                  and requester_id = ?
                  and status = 'APPROVED'
            )
            """, Boolean.class, requestId, recipientId);

        if (!Boolean.TRUE.equals(ownsRequest)) {
            throw new RuleViolation("FORBIDDEN");
        }

        UUID listingId;

        try {
            listingId = jdbc.queryForObject("""
                update handovers h
                set status = 'COLLECTED',
                    completed_at = clock_timestamp()
                from requests r
                where h.request_id = r.id
                  and r.id = ?
                  and r.status = 'APPROVED'
                  and h.status = 'AWAITING_COLLECTION'
                returning r.listing_id
                """, UUID.class, requestId);

        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new RuleViolation("HANDOVER_NOT_PENDING");
        }

        jdbc.update("""
            update listings
            set status = 'COLLECTED',
                updated_at = clock_timestamp()
            where id = ?
              and status = 'RESERVED'
            """, listingId);

        activity.record(
                recipientId,
                listingId,
                "COLLECTED"
        );
    }
}
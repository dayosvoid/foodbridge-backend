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

        var row = jdbc.query("""
            select l.id as listing_id,
                   l.donor_id,
                   l.status as listing_status,
                   r.status as request_status,
                   h.status as handover_status
            from requests r
            join listings l on l.id = r.listing_id
            left join handovers h on h.request_id = r.id
            where r.id = ?
            for update of r, l
            """,
                (rs, n) -> new Object[]{
                        rs.getObject("listing_id", UUID.class),
                        rs.getObject("donor_id", UUID.class),
                        rs.getString("listing_status"),
                        rs.getString("request_status"),
                        rs.getString("handover_status")
                },
                requestId
        ).stream().findFirst().orElseThrow(
                () -> new RuleViolation("REQUEST_NOT_FOUND")
        );

        UUID listingId = (UUID) row[0];
        UUID donorId = (UUID) row[1];
        String listingStatus = (String) row[2];
        String requestStatus = (String) row[3];
        String handoverStatus = (String) row[4];

        if (!admin && !actorId.equals(donorId)) {
            throw new RuleViolation("FORBIDDEN");
        }

        if (!"APPROVED".equals(requestStatus)
                || !"AWAITING_COLLECTION".equals(handoverStatus)) {
            throw new RuleViolation("HANDOVER_NOT_PENDING");
        }

        if (!"RESERVED".equals(listingStatus)) {
            throw new RuleViolation("LISTING_NOT_RESERVED");
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

        var row = jdbc.query("""
            select l.id as listing_id,
                   l.donor_id,
                   l.status as listing_status,
                   h.status as handover_status
            from handovers h
            join requests r on r.id = h.request_id
            join listings l on l.id = r.listing_id
            where h.id = ?
            for update of h, l
            """,
                (rs, n) -> new Object[]{
                        rs.getObject("listing_id", UUID.class),
                        rs.getObject("donor_id", UUID.class),
                        rs.getString("listing_status"),
                        rs.getString("handover_status")
                },
                handoverId
        ).stream().findFirst().orElseThrow(
                () -> new RuleViolation("HANDOVER_NOT_FOUND")
        );

        UUID listingId = (UUID) row[0];
        UUID donorId = (UUID) row[1];
        String listingStatus = (String) row[2];
        String handoverStatus = (String) row[3];

        if (!admin && !actorId.equals(donorId)) {
            throw new RuleViolation("FORBIDDEN");
        }

        if (!"COLLECTED".equals(handoverStatus)) {
            throw new RuleViolation("HANDOVER_NOT_COLLECTED");
        }

        if (!"COLLECTED".equals(listingStatus)) {
            throw new RuleViolation("LISTING_NOT_COLLECTED");
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
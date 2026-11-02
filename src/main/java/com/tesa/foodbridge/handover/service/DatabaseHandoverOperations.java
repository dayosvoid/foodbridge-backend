package com.tesa.foodbridge.handover.service;

import com.tesa.foodbridge.activity.api.ActivityRecorder;
import com.tesa.foodbridge.handover.api.HandoverOperations;
import com.tesa.foodbridge.shared.RuleViolation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
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
    public UUID completeByDonorOrAdmin(
            UUID requestId,
            UUID actorId,
            boolean admin) {

        var row = jdbc.query("""
            select l.id as listing_id,
                   l.donor_id,
                   l.status as listing_status,
                   r.status as request_status,
                   h.id as handover_id,
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
                        rs.getObject("handover_id", UUID.class),
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
        UUID handoverId = (UUID) row[4];
        String handoverStatus = (String) row[5];

        if (!admin && !actorId.equals(donorId)) {
            throw new RuleViolation("FORBIDDEN");
        }

        if (!"APPROVED".equals(requestStatus)
                || handoverId == null
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
            where id = ?
              and status = 'AWAITING_COLLECTION'
            """, handoverId);

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

        return handoverId;
    }

    @Override
    @Transactional
    public UUID reverseByDonorOrAdmin(
            UUID handoverOrRequestId,
            UUID actorId,
            boolean admin) {

        var row = jdbc.query("""
            select l.id as listing_id,
                   l.donor_id,
                   l.status as listing_status,
                   r.id as request_id,
                   r.status as request_status,
                   h.id as handover_id,
                   h.status as handover_status
            from handovers h
            join requests r on r.id = h.request_id
            join listings l on l.id = r.listing_id
            where h.id = ? or r.id = ?
            for update of h, r, l
            """,
                (rs, n) -> new Object[]{
                        rs.getObject("listing_id", UUID.class),
                        rs.getObject("donor_id", UUID.class),
                        rs.getString("listing_status"),
                        rs.getObject("request_id", UUID.class),
                        rs.getString("request_status"),
                        rs.getObject("handover_id", UUID.class),
                        rs.getString("handover_status")
                },
                handoverOrRequestId,
                handoverOrRequestId
        ).stream().findFirst().orElseThrow(
                () -> new RuleViolation("HANDOVER_NOT_FOUND")
        );

        UUID listingId = (UUID) row[0];
        UUID donorId = (UUID) row[1];
        String listingStatus = (String) row[2];
        UUID requestId = (UUID) row[3];
        String requestStatus = (String) row[4];
        UUID handoverId = (UUID) row[5];
        String handoverStatus = (String) row[6];

        if (!admin && !actorId.equals(donorId)) {
            throw new RuleViolation("FORBIDDEN");
        }

        if (!"APPROVED".equals(requestStatus)
                || !Set.of("AWAITING_COLLECTION", "COLLECTED").contains(handoverStatus)) {
            throw new RuleViolation("HANDOVER_NOT_COLLECTED");
        }

        if (!Set.of("RESERVED", "COLLECTED").contains(listingStatus)) {
            throw new RuleViolation("LISTING_NOT_COLLECTED");
        }

        int handoverUpdated = jdbc.update("""
            update handovers
            set status = 'REVERSED',
                reversed_at = clock_timestamp()
            where id = ?
              and status in ('AWAITING_COLLECTION', 'COLLECTED')
            """, handoverId);

        if (handoverUpdated != 1) {
            throw new RuleViolation("HANDOVER_NOT_COLLECTED");
        }

        int requestUpdated = jdbc.update("""
            update requests
            set status = 'REVERSED',
                decided_at = clock_timestamp()
            where id = ?
              and status = 'APPROVED'
            """, requestId);

        if (requestUpdated != 1) {
            throw new RuleViolation("HANDOVER_NOT_COLLECTED");
        }

        int listingUpdated = jdbc.update("""
            update listings
            set status = case
                    when collection_deadline > clock_timestamp() then 'AVAILABLE'
                    else 'EXPIRED'
                end,
                updated_at = clock_timestamp()
            where id = ?
              and status in ('RESERVED', 'COLLECTED')
            """, listingId);

        if (listingUpdated != 1) {
            throw new RuleViolation("LISTING_NOT_COLLECTED");
        }

        activity.record(actorId, listingId, "REVERSED");

        return handoverId;
    }
}

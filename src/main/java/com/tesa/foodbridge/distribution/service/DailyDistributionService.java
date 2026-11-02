package com.tesa.foodbridge.distribution.service;

import com.tesa.foodbridge.distribution.api.DailyDistributionOperations;
import com.tesa.foodbridge.distribution.api.DailyDistributionSummary;
import com.tesa.foodbridge.shared.RuleViolation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DailyDistributionService
        implements DailyDistributionOperations {

    private final JdbcTemplate jdbc;

    public DailyDistributionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ensures that every registered recipient gets a first
     * approved listing before any recipient gets a second one.
     *
     * Must execute inside the approval transaction.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertRecipientEligibleForApproval(
            UUID recipientId) {

        // Serializes approval decisions across different listings.
        jdbc.query(
                "select pg_advisory_xact_lock(741203, 1)",
                rs -> {}
        );

        boolean recipientAlreadyServed =
                Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (
                        select 1
                        from requests r
                        where r.requester_id = ?
                          and r.status = 'APPROVED'
                          and r.decided_at is not null
                          and (r.decided_at at time zone 'Africa/Lagos')::date =
                              (current_timestamp at time zone 'Africa/Lagos')::date
                    )
                    """, Boolean.class, recipientId));

        if (!recipientAlreadyServed) {
            return;
        }

        boolean anotherRecipientWaiting =
                Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (
                        select 1
                        from users u
                        where u.role = 'RECIPIENT'
                          and not exists (
                              select 1
                              from requests r
                              where r.requester_id = u.id
                                and r.status = 'APPROVED'
                                and r.decided_at is not null
                                and (r.decided_at at time zone 'Africa/Lagos')::date =
                                    (current_timestamp at time zone 'Africa/Lagos')::date
                          )
                    )
                    """, Boolean.class));

        if (anotherRecipientWaiting) {
            throw new RuleViolation(
                    "DAILY_DISTRIBUTION_PRIORITY"
            );
        }
    }

    /**
     * Returns the current daily distribution statistics.
     */
    @Override
    @Transactional(readOnly = true)
    public DailyDistributionSummary getTodaySummary() {

        long recipients = count("""
                select count(*)
                from users
                where role = 'RECIPIENT'
                """);

        long availableListings = count("""
                select count(*)
                from listings
                where status = 'AVAILABLE'
                  and collection_deadline > current_timestamp
                """);

        long approvedToday = count("""
                select count(*)
                from requests r
                where r.status = 'APPROVED'
                  and r.decided_at is not null
                  and (r.decided_at at time zone 'Africa/Lagos')::date =
                      (current_timestamp at time zone 'Africa/Lagos')::date
                """);

        long servedToday = count("""
                select count(distinct r.requester_id)
                from requests r
                where r.status = 'APPROVED'
                  and r.decided_at is not null
                  and (r.decided_at at time zone 'Africa/Lagos')::date =
                      (current_timestamp at time zone 'Africa/Lagos')::date
                """);

        long waiting = Math.max(0, recipients - servedToday);

        return new DailyDistributionSummary(
                recipients,
                availableListings,
                approvedToday,
                servedToday,
                waiting,
                waiting > 0
        );
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
package com.tesa.foodbridge.dashboard.repository;

import com.tesa.foodbridge.dashboard.api.AdminDashboardResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminDashboardRepository {

    private final JdbcTemplate jdbc;

    public AdminDashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminDashboardResponse getStatistics() {

        return jdbc.queryForObject("""
            SELECT
                (SELECT COUNT(*) FROM users)
                    AS total_users,

                (SELECT COUNT(*) FROM users
                 WHERE role = 'DONOR')
                    AS total_donors,

                (SELECT COUNT(*) FROM users
                 WHERE role = 'RECIPIENT')
                    AS total_recipients,

                (SELECT COUNT(*) FROM listings
                 WHERE status <> 'DELETED')
                    AS total_listings,

                (SELECT COUNT(*) FROM listings
                 WHERE status = 'AVAILABLE'
                   AND collection_deadline > clock_timestamp())
                    AS available_listings,

                (SELECT COUNT(*) FROM listings
                 WHERE status = 'RESERVED')
                    AS reserved_listings,

                (SELECT COUNT(*) FROM listings
                 WHERE status = 'COLLECTED')
                    AS collected_listings,

                (SELECT COUNT(*) FROM listings
                 WHERE status = 'EXPIRED'
                    OR (status = 'AVAILABLE'
                        AND collection_deadline <= clock_timestamp()))
                    AS expired_listings,

                (SELECT COUNT(*) FROM requests)
                    AS total_requests,

                (SELECT COUNT(*) FROM requests
                 WHERE status = 'PENDING')
                    AS pending_requests,

                (SELECT COUNT(*) FROM handovers
                 WHERE status = 'COLLECTED')
                    AS completed_handovers,

                (SELECT COALESCE(SUM(
                    CASE
                        WHEN h.portions_estimate IS NOT NULL THEN h.portions_estimate::bigint
                        WHEN l.unit = 'PORTIONS' THEN ROUND(l.quantity)::bigint
                        ELSE 0
                    END
                 ), 0)
                 FROM handovers h
                 JOIN requests r ON r.id = h.request_id
                 JOIN listings l ON l.id = r.listing_id
                 WHERE h.status = 'COLLECTED')
                    AS estimated_portions_saved
            """,
                (rs, rowNum) -> new AdminDashboardResponse(
                        rs.getLong("total_users"),
                        rs.getLong("total_donors"),
                        rs.getLong("total_recipients"),
                        rs.getLong("total_listings"),
                        rs.getLong("available_listings"),
                        rs.getLong("reserved_listings"),
                        rs.getLong("collected_listings"),
                        rs.getLong("expired_listings"),
                        rs.getLong("total_requests"),
                        rs.getLong("pending_requests"),
                        rs.getLong("completed_handovers"),
                        rs.getLong("estimated_portions_saved")
                )
        );
    }
}
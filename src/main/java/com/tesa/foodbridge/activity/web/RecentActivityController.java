
package com.tesa.foodbridge.activity.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/activity")
public class RecentActivityController {

    private final JdbcTemplate jdbc;

    public RecentActivityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ActivityResponse(
            UUID id,
            String actorName,
            String type,
            UUID listingId,
            String listingTitle,
            String details,
            Instant createdAt
    ) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth"
    )
    public List<ActivityResponse> getRecentActivity(
            @RequestParam(defaultValue = "20") int limit) {

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Limit must be between 1 and 100"
            );
        }

        return jdbc.query("""
            SELECT
                a.id,
                u.display_name AS actor_name,
                a.type,
                a.listing_id,
                l.title AS listing_title,
                a.details,
                a.created_at
            FROM activity a
            LEFT JOIN users u ON u.id = a.actor_id
            LEFT JOIN listings l ON l.id = a.listing_id
            ORDER BY a.created_at DESC
            LIMIT ?
            """,
                (rs, rowNum) -> new ActivityResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("type"),
                        rs.getObject("listing_id", UUID.class),
                        rs.getString("listing_title"),
                        rs.getString("details"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                limit
        );
    }
}

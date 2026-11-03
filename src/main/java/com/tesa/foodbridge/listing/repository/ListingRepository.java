
package com.tesa.foodbridge.listing.repository;

import com.tesa.foodbridge.listing.api.ListingOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class ListingRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ListingView> MAPPER =
            (r, n) -> new ListingView(
                    r.getObject("id", UUID.class),
                    r.getObject("donor_id", UUID.class),
                    r.getString("donor_name"),
                    r.getString("title"),
                    r.getString("description"),
                    r.getString("category"),
                    r.getBigDecimal("quantity"),
                    r.getString("unit"),
                    r.getString("image_url"),
                    r.getString("pickup_area"),
                    r.getTimestamp("collection_deadline").toInstant(),
                    r.getString("status"),
                    r.getTimestamp("created_at").toInstant(),
                    r.getTimestamp("updated_at").toInstant(),
                    r.getInt("pending_request_count"),
                    r.getTimestamp("expired_at")==null?null:r.getTimestamp("expired_at").toInstant(),
                    r.getString("expiration_reason")
            );

    private static final String SELECT = """
        select l.*, u.display_name as donor_name,
               (select count(*) from requests r
                where r.listing_id = l.id
                and r.status = 'PENDING')
                as pending_request_count
        from listings l
        join users u on u.id = l.donor_id
        """;

    public ListingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ListingView insert(UUID id, UUID donor, CreateListing c) {

        jdbc.update("""
            insert into listings(
                id, donor_id, title, description, category,
                quantity, unit, image_url, pickup_area,
                collection_deadline
            )
            values (?,?,?,?,?,?,?,?,?,?)
            """,
                id,
                donor,
                c.title(),
                c.description(),
                c.category(),
                c.quantity(),
                c.unit(),
                c.imageUrl(),
                c.pickupArea(),
                Timestamp.from(c.collectionDeadline())
        );

        return jdbc.queryForObject(
                SELECT + " where l.id=?",
                MAPPER,
                id
        );
    }

    public List<ListingView> findLocked(UUID id) {
        return jdbc.query(
                SELECT + " where l.id=? for update of l",
                MAPPER,
                id
        );
    }

    public List<ListingView> find(UUID id) {
        return jdbc.query(
                SELECT + " where l.id=?",
                MAPPER,
                id
        );
    }

    public List<ListingView> browse(int offset, int limit) {
        return jdbc.query(
                SELECT
                        + " where l.status<>'DELETED' order by l.created_at desc,l.id "
                        + "limit ? offset ?",
                MAPPER,
                limit,
                offset
        );
    }

    public ListingPage browse(ListingFilter filter, int offset, int limit) {
        return queryFiltered(null, filter, false, offset, limit);
    }

    public ListingPage browseAdmin(ListingFilter filter, int offset, int limit) {
        return queryFiltered(null, filter, true, offset, limit);
    }

    public List<ListingView> browseMine(
            UUID donorId, int offset, int limit) {

        return jdbc.query(
                SELECT
                        + " where l.donor_id=? and l.status<>'DELETED' "
                        + "order by l.created_at desc,l.id "
                        + "limit ? offset ?",
                MAPPER,
                donorId,
                limit,
                offset
        );
    }

    public ListingPage browseMine(
            UUID donorId, ListingFilter filter, int offset, int limit) {
        return queryFiltered(donorId, filter, false, offset, limit);
    }

    private ListingPage queryFiltered(
            UUID donorId,
            ListingFilter filter,
            boolean includeDeleted,
            int offset,
            int limit) {

        ListingFilter f = filter == null ? ListingFilter.NONE : filter;
        Timestamp now = Timestamp.from(java.time.Instant.now());
        java.util.List<Object> params = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(
                includeDeleted ? " where 1=1" : " where l.status<>'DELETED'"
        );

        if (donorId != null) {
            where.append(" and l.donor_id=?");
            params.add(donorId);
        }

        if (f.category() != null && !f.category().isBlank()) {
            where.append(" and upper(l.category)=upper(?)");
            params.add(f.category().trim());
        }

        if (f.pickupArea() != null && !f.pickupArea().isBlank()) {
            where.append(" and upper(l.pickup_area)=upper(?)");
            params.add(f.pickupArea().trim());
        }

        if (f.keyword() != null && !f.keyword().isBlank()) {
            String pattern = "%" + f.keyword().trim().toLowerCase(java.util.Locale.ROOT) + "%";
            where.append(" and (lower(l.title) like ? or lower(l.description) like ?)");
            params.add(pattern);
            params.add(pattern);
        }

        if (f.availability() != null && !f.availability().isBlank()) {
            String avail = f.availability().trim().toUpperCase(java.util.Locale.ROOT);
            switch (avail) {
                case "AVAILABLE" -> {
                    where.append(" and l.status='AVAILABLE' and l.collection_deadline > ?");
                    params.add(now);
                }
                case "EXPIRED" -> {
                    if (includeDeleted) {
                        where.append(" and (l.status in ('EXPIRED', 'DELETED') or (l.status='AVAILABLE' and l.collection_deadline <= ?))");
                    } else {
                        where.append(" and (l.status='EXPIRED' or (l.status='AVAILABLE' and l.collection_deadline <= ?))");
                    }
                    params.add(now);
                }
                case "RESERVED", "COLLECTED", "REVERSED" -> {
                    where.append(" and l.status=?");
                    params.add(avail);
                }
                default -> {
                    where.append(" and l.status=?");
                    params.add(avail);
                }
            }
        }

        Long total = jdbc.queryForObject(
                "select count(*) from listings l join users u on u.id = l.donor_id" + where,
                Long.class,
                params.toArray()
        );

        java.util.List<Object> pageParams = new java.util.ArrayList<>(params);
        pageParams.add(limit);
        pageParams.add(offset);

        List<ListingView> items = jdbc.query(
                SELECT + where + " order by l.created_at desc,l.id limit ? offset ?",
                MAPPER,
                pageParams.toArray()
        );

        return new ListingPage(items, total == null ? 0L : total);
    }

    public boolean deadlinePassed(UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("""
                    select collection_deadline <= clock_timestamp()
                    from listings
                    where id=?
                    """,
                        Boolean.class,
                        id
                )
        );
    }

    public void reserve(UUID id) {
        jdbc.update("""
            update listings
            set status = 'RESERVED',
                updated_at = clock_timestamp()
            where id = ?
            """,
                id
        );
    }

    /** Called while holding the listing row lock in the deletion transaction. */
    public String archiveDetails(UUID id) {
        return jdbc.queryForObject("""
            select jsonb_build_object('previous_status', l.status,
                'listing', to_jsonb(l))::text from listings l where l.id=?
            """, String.class, id);
    }

    public int markDeleted(UUID id) {
        return jdbc.update("""
            update listings set status='DELETED', updated_at=clock_timestamp()
            where id=? and status in ('AVAILABLE', 'EXPIRED')
            """, id);
    }

    public boolean hasRequest(UUID listingId,UUID requesterId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from requests where listing_id=? and requester_id=?)",
                Boolean.class,listingId,requesterId));
    }

    // Edit listing
    public ListingView update(UUID id, CreateListing c) {

        jdbc.update("""
            update listings
            set title = ?,
                description = ?,
                category = ?,
                quantity = ?,
                unit = ?,
                image_url = ?,
                pickup_area = ?,
                collection_deadline = ?,
                updated_at = clock_timestamp()
            where id = ?
              and status = 'AVAILABLE'
            """,
                c.title(),
                c.description(),
                c.category(),
                c.quantity(),
                c.unit(),
                c.imageUrl(),
                c.pickupArea(),
                Timestamp.from(c.collectionDeadline()),
                id
        );

        return find(id).stream()
                .findFirst()
                .orElseThrow(() ->
                        new RuntimeException("Listing not found"));
    }

}

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
                    r.getTimestamp(
                            "collection_deadline"
                    ).toInstant(),
                    r.getString("status"),
                    r.getTimestamp(
                            "created_at"
                    ).toInstant(),
                    r.getTimestamp(
                            "updated_at"
                    ).toInstant(),
                    r.getInt(
                            "pending_request_count"
                    )
            );

    private static final String SELECT = """
        select l.*, u.display_name as donor_name,
               (select count(*) from requests r
                where r.listing_id=l.id
                and r.status='PENDING')
                as pending_request_count
        from listings l
        join users u on u.id=l.donor_id
        """;

    public ListingRepository(
            JdbcTemplate jdbc) {

        this.jdbc = jdbc;
    }

    public ListingView insert(
            UUID id,
            UUID donor,
            CreateListing c) {

        jdbc.update("""
            insert into listings(
                id,
                donor_id,
                title,
                description,
                category,
                quantity,
                unit,
                image_url,
                pickup_area,
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
                Timestamp.from(
                        c.collectionDeadline()
                )
        );

        return jdbc.queryForObject(
                SELECT + " where l.id=?",
                MAPPER,
                id
        );
    }

    public List<ListingView> findLocked(
            UUID id) {

        return jdbc.query(
                SELECT
                        + " where l.id=? for update of l",
                MAPPER,
                id
        );
    }

    public List<ListingView> find(
            UUID id) {

        return jdbc.query(
                SELECT + " where l.id=?",
                MAPPER,
                id
        );
    }

    public List<ListingView> browse(
            int offset,
            int limit) {

        return jdbc.query(
                SELECT
                        + " order by l.created_at desc,l.id "
                        + "limit ? offset ?",
                MAPPER,
                limit,
                offset
        );
    }

    public List<ListingView> browseMine(
            UUID donorId,
            int offset,
            int limit) {

        return jdbc.query(
                SELECT
                        + " where l.donor_id=? "
                        + "order by l.created_at desc,l.id "
                        + "limit ? offset ?",
                MAPPER,
                donorId,
                limit,
                offset
        );
    }

    public boolean deadlinePassed(
            UUID id) {

        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        select collection_deadline
                               <= clock_timestamp()
                        from listings
                        where id=?
                        """,
                        Boolean.class,
                        id
                )
        );
    }

    public void reserve(UUID id) {

        jdbc.update(
                """
                update listings
                set status='RESERVED',
                    updated_at=clock_timestamp()
                where id=?
                """,
                id
        );
    }
}
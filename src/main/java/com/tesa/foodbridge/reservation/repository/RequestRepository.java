package com.tesa.foodbridge.reservation.repository;

import com.tesa.foodbridge.reservation.api.ReservationOperations.RequestView;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class RequestRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<RequestView> MAPPER =
            (r, n) -> new RequestView(
                    r.getObject(
                            "id",
                            UUID.class
                    ),
                    r.getObject(
                            "listing_id",
                            UUID.class
                    ),
                    r.getObject(
                            "requester_id",
                            UUID.class
                    ),
                    r.getString(
                            "requester_name"
                    ),
                    r.getString(
                            "status"
                    ),
                    r.getTimestamp(
                            "created_at"
                    ).toInstant(),
                    r.getTimestamp(
                            "decided_at"
                    ) == null
                            ? null
                            : r.getTimestamp(
                            "decided_at"
                    ).toInstant()
            );

    private static final String SELECT = """
        select r.*, u.display_name as requester_name
        from requests r
        join users u on u.id=r.requester_id
        """;

    public RequestRepository(
            JdbcTemplate jdbc) {

        this.jdbc = jdbc;
    }

    public Optional<RequestView> find(
            UUID id) {

        return jdbc.query(
                        SELECT + " where r.id=?",
                        MAPPER,
                        id
                )
                .stream()
                .findFirst();
    }

    public Optional<RequestView> byKey(
            String key) {

        return jdbc.query(
                        SELECT
                                + " where r.idempotency_key=?",
                        MAPPER,
                        key
                )
                .stream()
                .findFirst();
    }

    public RequestView insert(
            UUID id,
            UUID listing,
            UUID actor,
            String key) {

        jdbc.update(
                """
                insert into requests(
                    id,
                    listing_id,
                    requester_id,
                    idempotency_key
                )
                values (?,?,?,?)
                """,
                id,
                listing,
                actor,
                key
        );

        return find(id)
                .orElseThrow();
    }

    public boolean approve(UUID id) {

        return jdbc.update(
                """
                update requests
                set status='APPROVED',
                    decided_at=clock_timestamp()
                where id=?
                  and status='PENDING'
                """,
                id
        ) == 1;
    }

    public boolean decline(UUID id) {

        return jdbc.update(
                """
                update requests
                set status='DECLINED',
                    decided_at=clock_timestamp()
                where id=?
                  and status='PENDING'
                """,
                id
        ) == 1;
    }

    public boolean cancel(
            UUID id,
            UUID requesterId) {

        return jdbc.update(
                """
                update requests
                set status='CANCELLED',
                    decided_at=clock_timestamp()
                where id=?
                  and requester_id=?
                  and status='PENDING'
                """,
                id,
                requesterId
        ) == 1;
    }

    public List<RequestView> findForListing(
            UUID listingId) {

        return jdbc.query(
                SELECT
                        + " where r.listing_id=? "
                        + "order by r.created_at desc,r.id",
                MAPPER,
                listingId
        );
    }

    public List<RequestView> findMine(
            UUID requesterId) {

        return jdbc.query(
                SELECT
                        + " where r.requester_id=? "
                        + "order by r.created_at desc,r.id",
                MAPPER,
                requesterId
        );
    }
}
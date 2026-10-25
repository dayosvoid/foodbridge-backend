package com.tesa.foodbridge.reservation.repository;

import com.tesa.foodbridge.reservation.api.ReservationOperations.RequestView;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class RequestRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<RequestView> MAPPER=(r,n)-> new RequestView(r.getObject("id",UUID.class),
        r.getObject("listing_id",UUID.class),r.getObject("requester_id",UUID.class),r.getString("status"),
        r.getTimestamp("created_at").toInstant(),r.getTimestamp("decided_at")==null?null:r.getTimestamp("decided_at").toInstant());
    public RequestRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<RequestView> find(UUID id) { return jdbc.query("select * from requests where id=?",MAPPER,id).stream().findFirst(); }
    public Optional<RequestView> byKey(String key) { return jdbc.query("select * from requests where idempotency_key=?",MAPPER,key).stream().findFirst(); }
    public RequestView insert(UUID id, UUID listing, UUID actor,String key) {
        jdbc.update("insert into requests(id,listing_id,requester_id,idempotency_key) values (?,?,?,?)",id,listing,actor,key);
        return find(id).orElseThrow();
    }
    public boolean approve(UUID id) {
        return jdbc.update("update requests set status='APPROVED',decided_at=clock_timestamp() where id=? and status='PENDING'",id)==1;
    }
}

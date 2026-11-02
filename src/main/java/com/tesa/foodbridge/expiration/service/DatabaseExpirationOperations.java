package com.tesa.foodbridge.expiration.service;

import com.tesa.foodbridge.expiration.api.ExpirationOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.Timestamp;
import java.util.*;

/** Owns cross-table lifecycle SQL; all command modules enter through ExpirationOperations. */
@Service
public class DatabaseExpirationOperations implements ExpirationOperations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private volatile UUID after;
    public DatabaseExpirationOperations(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean expireLocked(UUID id) { return process(id); }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockAggregate(UUID id) {
        jdbc.queryForList("select id from listings where id=? for update",id);
        jdbc.queryForList("select id from requests where listing_id=? order by id for update",id);
        jdbc.queryForList("select h.id from handovers h join requests r on r.id=h.request_id where r.listing_id=? order by h.id for update of h",id);
    }

    private boolean process(UUID id) {
        var listing=jdbc.queryForMap("select * from listings where id=? for update",id);
        // Explicit order shared by command paths; clock is sampled only after all locks.
        var requests=jdbc.queryForList("select * from requests where listing_id=? order by id for update",id);
        var handovers=jdbc.queryForList("select h.* from handovers h join requests r on r.id=h.request_id where r.listing_id=? order by h.id for update of h",id);
        Timestamp now=decisionTime();
        Timestamp deadline=(Timestamp)listing.get("collection_deadline");
        String state=(String)listing.get("status");
        if (!Set.of("AVAILABLE","RESERVED","EXPIRED").contains(state)) return false;
        if (!state.equals("EXPIRED") && deadline.after(now)) return false;
        if (state.equals("EXPIRED") && deadline.after(now)) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Expiration contradiction: legacy listing {} has a future deadline",id);
            return true;
        }
        // Completed history contradicting an open/expired listing must never be rewritten.
        if (handovers.stream().anyMatch(h->Set.of("COLLECTED","REVERSED").contains(h.get("status"))
                || h.get("completed_at")!=null || h.get("reversed_at")!=null)) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Expiration contradiction: listing {} has completed handover history",id);
            return true;
        }
        boolean legacy=state.equals("EXPIRED");
        String reason=legacy?"LEGACY_EXPIRATION_RECONCILIATION":"COLLECTION_DEADLINE_PASSED";
        var changedRequests=new ArrayList<Map<String,Object>>();
        var changedHandovers=new ArrayList<Map<String,Object>>();
        for(var r:requests) {
            if (!Set.of("PENDING","APPROVED").contains(r.get("status"))) continue;
            jdbc.update("update requests set status='EXPIRED', decided_at=coalesce(decided_at,?), expired_at=?, expiration_reason=? where id=?",
                    now,now,reason,r.get("id"));
            changedRequests.add(Map.of("id",r.get("id").toString(),"previous_status",r.get("status")));
        }
        for(var h:handovers) {
            if (!h.get("status").equals("AWAITING_COLLECTION")) continue;
            jdbc.update("update handovers set status='EXPIRED',expired_at=?,expiration_reason=? where id=?",now,reason,h.get("id"));
            changedHandovers.add(Map.of("id",h.get("id").toString(),"previous_status",h.get("status")));
        }
        if (!legacy || !changedRequests.isEmpty() || !changedHandovers.isEmpty()) {
            // Do not invent metadata for a previously expired listing.
            if (!legacy) jdbc.update("update listings set status='EXPIRED',expired_at=?,expiration_reason=?,updated_at=? where id=?",now,reason,now,id);
            try {
                String details=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                        "reason",reason,"listing_id",id.toString(),"previous_status",state,
                        "requests",changedRequests,"handovers",changedHandovers));
                jdbc.update("insert into activity(id,actor_id,listing_id,type,details,created_at) values (?,null,?,'LISTING_EXPIRED',?,?)",UUID.randomUUID(),id,details,now);
            } catch(java.io.IOException e) { throw new IllegalStateException(e); }
        }
        return true;
    }
    @Scheduled(fixedDelayString="${foodbridge.expiration.delay-ms:60000}", initialDelayString="${foodbridge.expiration.initial-delay-ms:60000}")
    public void expireDue() {
        // At most 100 aggregates per pass; independent transaction and SKIP LOCKED claim per row.
        UUID cursor=after;
        var candidates=jdbc.queryForList("select l.id from listings l where ((l.status in ('AVAILABLE','RESERVED') and l.collection_deadline<=clock_timestamp()) or (l.status='EXPIRED' and (exists(select 1 from requests r where r.listing_id=l.id and r.status in ('PENDING','APPROVED')) or exists(select 1 from handovers h join requests r on r.id=h.request_id where r.listing_id=l.id and h.status='AWAITING_COLLECTION')))) and (?::uuid is null or l.id>?::uuid) order by l.id limit 100",UUID.class,cursor,cursor);
        // Rotate across passes so contradictory or temporarily locked rows cannot starve later IDs.
        after=candidates.isEmpty()?null:candidates.getLast();
        for(UUID id:candidates) tx.executeWithoutResult(s->{
            var claimed=jdbc.queryForList("select id from listings where id=? for update skip locked",UUID.class,id);
            if(!claimed.isEmpty()) process(id);
        });
    }
    /** Separate seam for deterministic boundary tests; production always samples PostgreSQL. */
    public Timestamp decisionTime() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class); }
}

package com.tesa.foodbridge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Own disposable container: never accepts an external DB URL. No application locks. */
class ConstraintEvidenceTest {
    @Test void databaseIndexIsNecessaryAndSufficientForAtMostOneApproval() throws Exception {
        try(var db=new PostgreSQLContainer<>("postgres:17-alpine")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load().migrate();
            UUID donor=UUID.randomUUID(),recipient=UUID.randomUUID(),listing=UUID.randomUUID();
            List<UUID> ids=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
            try(var c=db.createConnection("")) {
                try(var s=c.prepareStatement("insert into users(id,email,password_hash,display_name,role) values (?,?,'disabled','Simulated',?)")) {
                    for(var pair:List.of(Map.entry(donor,"DONOR"),Map.entry(recipient,"RECIPIENT"))) {
                        s.setObject(1,pair.getKey());s.setString(2,pair.getKey()+"@example.invalid");s.setString(3,pair.getValue());s.executeUpdate();
                    }
                }
                try(var s=c.prepareStatement("insert into listings(id,donor_id,title,description,category,quantity,unit,pickup_area,collection_deadline) values (?,?,'Test','Test','OTHER',1,'ITEMS','Lagos',clock_timestamp()+interval '1 day')")) {
                    s.setObject(1,listing);s.setObject(2,donor);s.executeUpdate();
                }
                for(UUID id:ids) try(var s=c.prepareStatement("insert into requests(id,listing_id,requester_id) values (?,?,?)")) {
                    s.setObject(1,id);s.setObject(2,listing);s.setObject(3,recipient);s.executeUpdate();
                }
                execute(c,"drop index one_approved_request_per_listing");
                try {
                    assertEquals(3,race(db,ids));
                    assertEquals(3,approved(c));
                    System.out.println("SLIDE4 UNPROTECTED: approved=3; at-most-one invariant FAILS (expected counterexample)");
                } finally {
                    execute(c,"update requests set status='PENDING',decided_at=null");
                    execute(c,"create unique index one_approved_request_per_listing on requests(listing_id) where status='APPROVED'");
                }
                assertEquals(1,race(db,ids));
                assertEquals(1,approved(c));
                System.out.println("SLIDE4 PROTECTED: approved=1; SQLSTATE 23505 conflicts=2; invariant PASSES");
            }
        }
    }
    private int race(PostgreSQLContainer<?> db,List<UUID> ids) throws Exception {
        var ready=new CountDownLatch(3); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(UUID id:ids) futures.add(pool.submit(()->{
                try(var c=db.createConnection("")) {
                    c.setAutoCommit(false);
                    try(var s=c.createStatement(); var r=s.executeQuery("select count(*) from requests where status='APPROVED'")) {
                        r.next(); assertEquals(0,r.getInt(1));
                    }
                    ready.countDown(); assertTrue(start.await(10,TimeUnit.SECONDS));
                    try(var s=c.prepareStatement("update requests set status='APPROVED',decided_at=clock_timestamp() where id=?")) {
                        s.setObject(1,id); s.executeUpdate(); c.commit(); return true;
                    } catch(SQLException e) { c.rollback(); assertEquals("23505",e.getSQLState()); return false; }
                }
            }));
            try {
                assertTrue(ready.await(15,TimeUnit.SECONDS)); start.countDown();
                int successes=0;
                for(var f:futures) if(f.get(30,TimeUnit.SECONDS)) successes++;
                return successes;
            } finally { start.countDown(); }
        }
    }
    private int approved(Connection c) throws SQLException {
        try(var s=c.createStatement(); var r=s.executeQuery("select count(*) from requests where status='APPROVED'")) { r.next(); return r.getInt(1); }
    }
    private void execute(Connection c,String sql) throws SQLException { try(var s=c.createStatement()) { s.execute(sql); } }
}

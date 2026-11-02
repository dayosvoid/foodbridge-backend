package com.tesa.foodbridge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleMigrationTest {
    @Test void preservesLegacyRowsAndChecksumsWithoutInventingExpirationTimes() throws Exception {
        try(var db=new PostgreSQLContainer<>("postgres:17-alpine")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("6").load().migrate();
            try(var c=db.createConnection("");var s=c.createStatement()) {
                s.execute("insert into users(id,email,password_hash,display_name,role) values ('00000000-0000-0000-0000-000000000001','legacy@example.invalid','disabled','Owner','DONOR')");
                s.execute("insert into listings(id,donor_id,title,description,category,quantity,unit,pickup_area,collection_deadline,status) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','Legacy','Test','OTHER',1,'ITEMS','Lagos','2000-01-01T00:00:00Z','EXPIRED')");
                // Both older APPROVED-request reversals and main's REVERSED-request history remain valid.
                for (String status : java.util.List.of("APPROVED", "REVERSED")) {
                    s.execute("insert into listings(id,donor_id,title,description,category,quantity,unit,pickup_area,collection_deadline,status) values (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','"+status+"','Test','OTHER',1,'ITEMS','Lagos','2000-01-01T00:00:00Z','REVERSED')");
                    s.execute("insert into requests(id,listing_id,requester_id,status,decided_at) select gen_random_uuid(),id,donor_id,'"+status+"',now()-interval '2 days' from listings where title='"+status+"'");
                    s.execute("insert into handovers(id,request_id,status,completed_at,reversed_at) select gen_random_uuid(),r.id,'REVERSED',now()-interval '1 day',now() from requests r join listings l on l.id=r.listing_id where l.title='"+status+"'");
                }
                String before=scalar(s,"select jsonb_agg(to_jsonb(l) order by id)::text from listings l");
                String requestsBefore=scalar(s,"select jsonb_agg(to_jsonb(r) order by id)::text from requests r");
                String handoversBefore=scalar(s,"select jsonb_agg(to_jsonb(h) order by id)::text from handovers h");
                String checksums=scalar(s,"select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history");
                var flyway=Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load();
                assertEquals(2,flyway.migrate().migrationsExecuted);flyway.validate();
                assertEquals("7,8",scalar(s,"select string_agg(version,',' order by installed_rank) from flyway_schema_history where version::int>6"));
                assertEquals(before,scalar(s,"select jsonb_agg(to_jsonb(l)-'expired_at'-'expiration_reason' order by id)::text from listings l"));
                assertEquals(requestsBefore,scalar(s,"select jsonb_agg(to_jsonb(r)-'expired_at'-'expiration_reason' order by id)::text from requests r"));
                assertEquals(handoversBefore,scalar(s,"select jsonb_agg(to_jsonb(h)-'expired_at'-'expiration_reason' order by id)::text from handovers h"));
                assertEquals(checksums,scalar(s,"select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version::int<=6"));
                assertEquals("true",scalar(s,"select (expired_at is null and expiration_reason is null)::text from listings"));
                assertThrows(java.sql.SQLException.class,()->s.execute("insert into activity(id,actor_id,listing_id,type,details) values ('00000000-0000-0000-0000-000000000003',null,'00000000-0000-0000-0000-000000000002','LISTING_CREATED','{}')"));
            }
        }
    }
    String scalar(java.sql.Statement s,String sql) throws Exception {try(var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
}

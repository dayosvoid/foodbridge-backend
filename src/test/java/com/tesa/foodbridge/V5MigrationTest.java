package com.tesa.foodbridge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class V5MigrationTest {
    @Test void upgradesV4WithoutChangingDomainDataOrPriorChecksums() throws Exception {
        try(var db=new PostgreSQLContainer<>("postgres:17-alpine")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("4").load().migrate();
            try(var c=db.createConnection(""); var s=c.createStatement()) {
                s.execute("insert into users values ('00000000-0000-0000-0000-000000000001','test@example.invalid','disabled','Owner','DONOR',now())");
                s.execute("insert into listings(id,donor_id,title,description,category,quantity,unit,pickup_area,collection_deadline,status) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','Food','Description','OTHER',1,'ITEMS','Lagos',now()+interval '1 day','DELETED')");
                String before=scalar(s,"select to_jsonb(l)::text from listings l");
                String history=scalar(s,"select jsonb_agg(to_jsonb(h) order by installed_rank)::text from flyway_schema_history h");
                var flyway=Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load();
                assertEquals(1,flyway.migrate().migrationsExecuted);
                flyway.validate();
                assertEquals("-1970444023",scalar(s,"select checksum::text from flyway_schema_history where version='5'"),
                        "The already-applied V5 migration must retain its checksum");
                assertEquals(before,scalar(s,"select to_jsonb(l)::text from listings l"));
                assertEquals(history,scalar(s,"select jsonb_agg(to_jsonb(h) order by installed_rank)::text from flyway_schema_history h where version<>'5'"));
                assertEquals("0",scalar(s,"select count(*)::text from activity"));
                for (String type : java.util.List.of("LISTING_CREATED", "REQUEST_CREATED", "REQUEST_APPROVED",
                        "REQUEST_DECLINED", "REQUEST_CANCELLED", "COLLECTED", "REVERSED", "LISTING_ARCHIVED")) {
                    s.execute("insert into activity(id,actor_id,listing_id,type,details) values (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','" + type + "','{}')");
                }
                assertEquals("8",scalar(s,"select count(distinct type)::text from activity"));
                assertThrows(SQLException.class,()->s.execute("insert into activity(id,actor_id,listing_id,type,details) values (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','UNKNOWN','{}')"));
            }
        }
    }
    String scalar(Statement s,String sql) throws SQLException {try(var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
}

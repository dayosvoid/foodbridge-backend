package com.tesa.foodbridge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

class V6MigrationTest {
    @Test void preservesLegacyRowsAndChecksumsWithoutInventingExpirationTimes() throws Exception {
        try(var db=new PostgreSQLContainer<>("postgres:17-alpine")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("5").load().migrate();
            try(var c=db.createConnection("");var s=c.createStatement()) {
                s.execute("insert into users(id,email,password_hash,display_name,role) values ('00000000-0000-0000-0000-000000000001','legacy@example.invalid','disabled','Owner','DONOR')");
                s.execute("insert into listings(id,donor_id,title,description,category,quantity,unit,pickup_area,collection_deadline,status) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','Legacy','Test','OTHER',1,'ITEMS','Lagos','2000-01-01T00:00:00Z','EXPIRED')");
                String before=scalar(s,"select to_jsonb(l)::text from listings l");
                String checksums=scalar(s,"select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history");
                var flyway=Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load();
                assertEquals(1,flyway.migrate().migrationsExecuted);flyway.validate();
                assertEquals(before,scalar(s,"select (to_jsonb(l)-'expired_at'-'expiration_reason')::text from listings l"));
                assertEquals(checksums,scalar(s,"select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version<>'6'"));
                assertEquals("true",scalar(s,"select (expired_at is null and expiration_reason is null)::text from listings"));
                assertThrows(java.sql.SQLException.class,()->s.execute("insert into activity(id,actor_id,listing_id,type,details) values ('00000000-0000-0000-0000-000000000003',null,'00000000-0000-0000-0000-000000000002','LISTING_CREATED','{}')"));
            }
        }
    }
    String scalar(java.sql.Statement s,String sql) throws Exception {try(var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
}

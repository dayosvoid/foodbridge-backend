package com.tesa.foodbridge.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Explicit fixture administration: owns no runtime business operations. Never enable on production. */
@Component
@Profile("demo & !prod & !production")
@ConditionalOnProperty(name="foodbridge.demo.seed-enabled",havingValue="true")
public class DemoSeed implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    public DemoSeed(JdbcTemplate jdbc,Environment environment) { this.jdbc=jdbc; this.environment=environment; }
    public static UUID id(String key) { return UUID.nameUUIDFromBytes(("foodbridge-simulated-"+key).getBytes(StandardCharsets.UTF_8)); }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        String hash=environment.getRequiredProperty("DEMO_PASSWORD_HASH");
        if(hash.isBlank()) throw new IllegalArgumentException("DEMO_PASSWORD_HASH is required");
        for(String name:new String[]{"donor1","donor2","recipient1","recipient2","recipient3","admin"}) {
            String role=name.startsWith("donor")?"DONOR":name.startsWith("recipient")?"RECIPIENT":"ADMIN";
            jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,?,?,?) on conflict do nothing",
                id(name),name+"@demo.foodbridge.invalid",hash,"SIMULATED "+name,role);
        }
        for(int i=1;i<=12;i++) {
            String state=switch(i) { case 5->"RESERVED";case 6->"COLLECTED";case 7->"REVERSED";case 11,12->"EXPIRED";default->"AVAILABLE"; };
            jdbc.update("""
                insert into listings(id,donor_id,title,description,category,quantity,unit,image_url,pickup_area,collection_deadline,status)
                values (?,?,?,?,'PREPARED_MEALS',20,'PORTIONS',?,'SIMULATED Lagos pickup',
                    clock_timestamp() + (? * interval '1 hour'),?) on conflict do nothing
                """,id("listing"+i),id(i%2==0?"donor2":"donor1"),
                "SIMULATED "+(i==10?"Community lunch surplus with assorted vegetables and rice for multiple recipient organizations collecting at the designated afternoon pickup point ":"Meal donation ")+i,
                "Fictional demonstration listing; no real food is offered.",i==9?null:"https://example.invalid/simulated-food.jpg",
                i>=11?-24:72,state);
            if(i==2 || i==3 || i==4 || i==5 || i==6 || i==7) {
                int number=i==3?3:1;
                for(int r=1;r<=number;r++) {
                    boolean approved=i>=5;
                    UUID request=id("request"+i+"-"+r);
                    jdbc.update("""
                        insert into requests(id,listing_id,requester_id,status,idempotency_key,decided_at)
                        values (?,?,?,?,?,case when ? then clock_timestamp() else null end) on conflict do nothing
                        """,request,id("listing"+i),id("recipient"+r),approved?"APPROVED":"PENDING","simulated-"+i+"-"+r,approved);
                    if(approved) jdbc.update("""
                        insert into handovers(id,request_id,status,portions_estimate,completed_at,reversed_at)
                        values (?,?,?,20,case when ? then clock_timestamp() else null end,
                            case when ? then clock_timestamp() else null end) on conflict do nothing
                        """,id("handover"+i),request,i==5?"AWAITING_COLLECTION":i==6?"COLLECTED":"REVERSED",i>=6,i==7);
                }
            }
        }
    }
}

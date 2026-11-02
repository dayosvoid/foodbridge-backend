package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.*;
import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.reservation.api.ReservationOperations;
import com.tesa.foodbridge.shared.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@org.springframework.test.context.ActiveProfiles("demo")
@SpringBootTest
@Testcontainers
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PresentationSeedPostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",DB::getJdbcUrl);
        properties.add("spring.datasource.username",DB::getUsername);
        properties.add("spring.datasource.password",DB::getPassword);
        properties.add("foodbridge.demo.seed-enabled",()->"false");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ListingOperations listings;
    @Autowired ReservationOperations requests;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    final ObjectMapper json=new ObjectMapper();
    final Path manifest=Path.of("disposable-test-only-backup.json");
    MockEnvironment env;
    @org.junit.jupiter.api.io.TempDir Path auditDirectory;
    BackupVerifier backup;
    PresentationSeedMaintenance maintenance;
    @BeforeEach void setup() {
        jdbc.execute("truncate activity,handovers,requests,listings,users");
        legacyFixtures();
        env=new MockEnvironment().withProperty("foodbridge.presentation.maintenance-enabled","true")
                .withProperty("foodbridge.presentation.expected-host",DB.getHost())
                .withProperty("foodbridge.presentation.audit-directory",auditDirectory.toString());
        env.setActiveProfiles("demo");
        backup=mock(BackupVerifier.class);
        // Only the disposable test DB uses mocked archive verification. The real CLI always uses BackupVerifier.
        when(backup.verify(any(),anyString(),anyString())).thenReturn("b".repeat(64));
        maintenance=new PresentationSeedMaintenance(jdbc,env,backup);
    }
    Actor actor(String key,Actor.Role role) {return new Actor(DemoSeed.id(key),role);}
    String ids(String prefix,int count) {
        return java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->"'"+DemoSeed.id(prefix+
                (prefix.equals("replacement-v1-listing-")?String.format(Locale.ROOT,"%02d",i):i))+"'::uuid")
                .collect(java.util.stream.Collectors.joining(","));
    }
    int count(String sql) {return jdbc.queryForObject(sql,Integer.class);}
    String snapshot(String table,String condition) {
        return jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by t.id),'[]'::jsonb)::text "
                +"from (select * from "+table+" "+condition+") t",String.class);
    }
    void apply(String token) {new TransactionTemplate(manager).executeWithoutResult(status->maintenance.apply(token,manifest));}
    String apply() {
        var preview=maintenance.preview(); assertTrue(preview.blockers().isEmpty(),preview.blockers().toString());
        apply(preview.approvalToken());return preview.approvalToken();
    }
    void assertInstalled() throws Exception {
        assertEquals(12,count("select count(*) from listings where status='DELETED' and id in ("+ids("listing",12)+")"));
        var data=DemoSeedDataset.load();
        var rows=jdbc.queryForList("select * from listings where id in ("+ids("replacement-v1-listing-",40)+")");
        assertEquals(40,rows.size());
        assertEquals(40,rows.stream().map(row->row.get("id")).distinct().count());
        int pictured=0;
        for(var row:rows) {
            JsonNode expected=DemoSeed.find(data.root().get("listings"),row.get("id").toString());
            assertEquals(DemoSeed.uuid(expected,"donor_id"),row.get("donor_id"));
            assertEquals("DONOR",jdbc.queryForObject("select role from users where id=?",String.class,row.get("donor_id")));
            assertEquals(expected.get("image_url").isNull()?null:expected.path("image_url").asText(),row.get("image_url"));
            if(row.get("image_url")!=null) {
                pictured++;
                assertTrue(row.get("image_url").toString().matches("https://res[.]cloudinary[.]com/qjcfxycq/image/upload/v[0-9]+/[A-Za-z0-9_-]+[.](jpg|webp)"));
            }
            assertFalse(((Timestamp)row.get("collection_deadline")).toInstant().isBefore(DemoSeedDataset.PRESENTATION_DEADLINE_FLOOR));
            assertTrue(((Timestamp)row.get("collection_deadline")).after((Timestamp)row.get("created_at")));
            assertFalse(((Timestamp)row.get("updated_at")).before((Timestamp)row.get("created_at")));
        }
        assertEquals(36,pictured);
        var audit=json.readTree(Path.of(maintenance.preview().affectedRecords().path("audit_journal").asText()).toFile());
        assertEquals(12,audit.path("before").path("legacy_listings").size());
        assertEquals(6,count("select count(*) from pg_constraint where contype='f' and convalidated and connamespace='public'::regnamespace"));
    }
    @Test void archivesAllTwelvePreservingTeamDataAndAuditsPreviousStatuses() throws Exception {
        // Recipient 2 has not been served: retain main's daily fairness rule.
        requests.approve(actor("donor1",Actor.Role.DONOR),DemoSeed.id("request3-2"));
        requests.request(actor("recipient1",Actor.Role.RECIPIENT),DemoSeed.id("listing1"),"team-interaction");
        var unrelatedIds=new ArrayList<UUID>();
        for(int i=0;i<4;i++) unrelatedIds.add(listings.create(actor("donor1",Actor.Role.DONOR),
                new ListingOperations.CreateListing("Team donation "+i,"Team-created content","PANTRY",BigDecimal.TEN,
                        "KG",null,"Yaba",Instant.now().plusSeconds(86400))).id());
        var users=snapshot("users","");var requestRows=snapshot("requests","");var handovers=snapshot("handovers","");
        var activity=snapshot("activity","");
        String unrelated=snapshot("listings","where id not in ("+ids("listing",12)+")");
        JsonNode before=json.readTree(snapshot("listings","where id in ("+ids("listing",12)+")"));
        String approved=apply();assertInstalled();
        assertEquals(users,snapshot("users",""));assertEquals(requestRows,snapshot("requests",""));assertEquals(handovers,snapshot("handovers",""));
        assertEquals(activity,snapshot("activity",""));
        assertEquals(unrelated,snapshot("listings","where id not in ("+ids("listing",12)+","+ids("replacement-v1-listing-",40)+")"));
        for(JsonNode original:before) {
            JsonNode details=json.readTree(Path.of(maintenance.preview().affectedRecords().path("audit_journal").asText()).toFile());
            assertEquals(original,DemoSeed.find(details.path("before").path("legacy_listings"),original.path("id").asText()));
            assertEquals(approved,details.path("approved_preview").asText());assertEquals("b".repeat(64),details.path("backup_sha256").asText());
        }
    }
    @Test void deletedRowsAreExcludedBeforePublicAndOwnerPagination() throws Exception {
        jdbc.update("update listings set created_at=clock_timestamp()+interval '1 day' where id in ("+ids("listing",12)+")");
        apply();assertInstalled();
        var all=new HashSet<UUID>();
        for(int offset=0;offset<40;offset+=3) {
            var page=listings.browse(offset,3);
            assertEquals(Math.min(3,40-offset),page.size());
            page.forEach(row->{assertNotEquals("DELETED",row.status());assertTrue(all.add(row.id()));});
        }
        assertEquals(40,all.size());assertTrue(listings.browse(40,3).isEmpty());
        for(String donor:List.of("donor1","donor2")) {
            var page=listings.browseMine(actor(donor,Actor.Role.DONOR),0,100);
            assertEquals(20,page.size());assertTrue(page.stream().noneMatch(row->row.status().equals("DELETED")));
        }
    }
    @Test void deletedListingsRejectRequestsAndApprovalsButPreserveAuthorizedHistory() {
        apply();UUID old=DemoSeed.id("listing2"),pending=DemoSeed.id("request2-1");
        var recipient=actor("recipient1",Actor.Role.RECIPIENT);var owner=actor("donor2",Actor.Role.DONOR);
        assertEquals("LISTING_NOT_FOUND",assertThrows(RuleViolation.class,()->requests.request(recipient,old,"new-request")).code());
        assertEquals("LISTING_NOT_FOUND",assertThrows(RuleViolation.class,()->requests.approve(owner,pending)).code());
        assertEquals("LISTING_NOT_FOUND",assertThrows(RuleViolation.class,()->listings.getPublic(old)).code());
        assertEquals("DELETED",listings.getHistorical(owner,old).status());
        assertEquals("DELETED",listings.getHistorical(recipient,old).status());
        assertEquals("DELETED",listings.getHistorical(actor("admin",Actor.Role.ADMIN),old).status());
        assertEquals("FORBIDDEN",assertThrows(RuleViolation.class,()->listings.getHistorical(actor("recipient3",Actor.Role.RECIPIENT),old)).code());
        assertEquals("FORBIDDEN",assertThrows(RuleViolation.class,()->listings.getHistorical(actor("donor1",Actor.Role.DONOR),old)).code());
        assertEquals(pending,requests.get(recipient,pending).id());
        assertEquals(pending,requests.request(recipient,old,"simulated-2-1").id()); // replay is not a new request
        assertEquals("CANCELLED",requests.cancel(recipient,pending).status());
        assertEquals("DECLINED",requests.decline(owner,DemoSeed.id("request4-1")).status());
    }
    @Test void historicalHttpDetailRequiresOwnerRequesterOrRealAdminIdentity() throws Exception {
        apply();
        String first="/api/listings/"+DemoSeed.id("listing1"),second="/api/listings/"+DemoSeed.id("listing2");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(first))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        for(String login:List.of("donor1:donor123","admin1:admin123"))
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(first).header("Authorization",bearer(login)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("DELETED"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(second).header("Authorization",bearer("recipient1:recipient123")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(second).header("Authorization",bearer("donor1:donor123")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    String bearer(String credentials) throws Exception {
        String[] parts=credentials.split(":",2);
        String email=(parts[0].equals("admin1")?"admin":parts[0])+"@demo.foodbridge.invalid";
        var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                .contentType("application/json").content(json.writeValueAsString(Map.of("email",email,"password",parts[1]))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        return "Bearer "+json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }
    @Test void rerunIsNoOpIncludingStatusesDeadlinesAndNewInteractions() throws Exception {
        String token=apply();
        UUID id=DemoSeed.id("replacement-v1-listing-01");
        UUID request=requests.request(actor("recipient2",Actor.Role.RECIPIENT),id,"new-demo-interaction").id();
        requests.approve(actor("donor1",Actor.Role.DONOR),request);
        var before=new LinkedHashMap<String,String>();
        for(String table:List.of("users","listings","requests","handovers","activity")) before.put(table,snapshot(table,""));
        apply(token);assertInstalled();
        before.forEach((table,rows)->assertEquals(rows,snapshot(table,"")));
    }
    @Test void staleApprovalAbortsWithoutAnyMutation() {
        String token=maintenance.preview().approvalToken();
        requests.request(actor("recipient1",Actor.Role.RECIPIENT),DemoSeed.id("listing8"),"new-after-preview");
        String before=snapshot("listings","");String activity=snapshot("activity","");
        assertTrue(assertThrows(IllegalStateException.class,()->apply(token)).getMessage().contains("changed since preview"));
        assertEquals(before,snapshot("listings",""));assertEquals(activity,snapshot("activity",""));
    }
    @Test void insertionFailureRollsBackArchivesAuditsAndAllInserts() {
        var before=new LinkedHashMap<String,String>();
        for(String table:List.of("users","listings","requests","handovers","activity")) before.put(table,snapshot(table,""));
        jdbc.execute("alter table listings add constraint reject_new_demo_test check (title like 'SIMULATED %')");
        try {assertThrows(RuntimeException.class,()->apply());}
        finally {jdbc.execute("alter table listings drop constraint reject_new_demo_test");}
        before.forEach((table,rows)->assertEquals(rows,snapshot(table,"")));
    }
    @Test void conflictingReplacementIdAbortsBeforeArchive() {
        jdbc.update("insert into listings(id,donor_id,title,description,category,quantity,unit,pickup_area,collection_deadline) "
                +"values (?,?,'Unrelated','Unrelated','OTHER',1,'ITEMS','Lagos',clock_timestamp()+interval '1 day')",
                DemoSeed.id("replacement-v1-listing-01"),DemoSeed.id("donor1"));
        String before=snapshot("listings","");
        assertTrue(maintenance.preview().blockers().stream().anyMatch(value->value.contains("conflict")));
        assertThrows(IllegalStateException.class,()->apply(maintenance.preview().approvalToken()));
        assertEquals(before,snapshot("listings",""));assertFalse(java.nio.file.Files.exists(Path.of(maintenance.preview().affectedRecords().path("audit_journal").asText())));
    }
    @Test void missingBackupVerificationBlocksAllWrites() {
        when(backup.verify(any(),anyString(),anyString())).thenThrow(new IllegalStateException("Invalid backup"));
        String before=snapshot("listings","");
        assertThrows(IllegalStateException.class,()->apply(maintenance.preview().approvalToken()));
        assertEquals(before,snapshot("listings",""));
    }
    @Test void archiveSchemaAndAuditAreRequiredAndNotAutoRepaired() {
        apply();
        try {java.nio.file.Files.writeString(Path.of(maintenance.preview().affectedRecords().path("audit_journal").asText()),"{}");}
        catch(java.io.IOException e) {throw new RuntimeException(e);}
        assertTrue(maintenance.preview().blockers().stream().anyMatch(value->value.contains("audit")));
        String before=snapshot("listings","");
        assertThrows(IllegalStateException.class,()->apply(maintenance.preview().approvalToken()));
        assertEquals(before,snapshot("listings",""));
    }
    @Test void readOnlyVerificationSqlDoesNotChangeRecords() throws Exception {
        apply();var before=snapshot("listings","");var activity=snapshot("activity","");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("docs/sql/verify-demo-seed.sql"));return null;
        });
        assertEquals(before,snapshot("listings",""));assertEquals(activity,snapshot("activity",""));assertInstalled();
    }

    void legacyFixtures() {
        for(String name:List.of("donor1","donor2","recipient1","recipient2","recipient3","admin"))
            jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,'disabled',?,?)",
                com.tesa.foodbridge.demo.DemoSeed.id(name),name+"@demo.foodbridge.invalid","SIMULATED "+name,
                name.startsWith("donor")?"DONOR":name.startsWith("recipient")?"RECIPIENT":"ADMIN");
        for(int i=1;i<=12;i++) {
            String state=switch(i) {case 5->"RESERVED";case 6->"COLLECTED";case 7->"REVERSED";case 11,12->"EXPIRED";default->"AVAILABLE";};
            jdbc.update("""
            insert into listings(id,donor_id,title,description,category,quantity,unit,image_url,pickup_area,collection_deadline,status)
            values (?,?,?,'Fictional demonstration listing; no real food is offered.',
                'PREPARED_MEALS',20,'PORTIONS',?,'SIMULATED Lagos pickup',
                clock_timestamp()+(? * interval '1 hour'),?)
            """,com.tesa.foodbridge.demo.DemoSeed.id("listing"+i),com.tesa.foodbridge.demo.DemoSeed.id(i%2==0?"donor2":"donor1"),
                "SIMULATED "+(i==10?"Community lunch surplus with assorted vegetables and rice for multiple recipient organizations collecting at the designated afternoon pickup point ":"Meal donation ")+i,
                i==9?null:"https://example.invalid/simulated-food.jpg",i>=11?-24:72,state);
            if(i<2 || i>7) continue;
            for(int r=1;r<=(i==3?3:1);r++) {
                UUID request=com.tesa.foodbridge.demo.DemoSeed.id("request"+i+"-"+r);
                jdbc.update("""
                    insert into requests(id,listing_id,requester_id,status,idempotency_key,decided_at)
                    values (?,?,?,?,?,case when ? then clock_timestamp() else null end)
                    """,request,com.tesa.foodbridge.demo.DemoSeed.id("listing"+i),com.tesa.foodbridge.demo.DemoSeed.id("recipient"+r),
                    i>=5?"APPROVED":"PENDING","simulated-"+i+"-"+r,i>=5);
                if(i>=5) jdbc.update("""
                    insert into handovers(id,request_id,status,portions_estimate,completed_at,reversed_at)
                    values (?,?,?,20,case when ? then clock_timestamp() else null end,case when ? then clock_timestamp() else null end)
                    """,com.tesa.foodbridge.demo.DemoSeed.id("handover"+i),request,
                    i==5?"AWAITING_COLLECTION":i==6?"COLLECTED":"REVERSED",i>=6,i==7);
            }
        }
    }

}

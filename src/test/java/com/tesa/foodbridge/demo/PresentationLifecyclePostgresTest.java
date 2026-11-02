package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.*;
import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.reservation.api.ReservationOperations;
import com.tesa.foodbridge.handover.api.HandoverOperations;
import com.tesa.foodbridge.expiration.api.ExpirationOperations;
import com.tesa.foodbridge.distribution.api.DailyDistributionOperations;
import com.tesa.foodbridge.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"foodbridge.demo.seed-enabled=false","foodbridge.expiration.initial-delay-ms=86400000"})
@ActiveProfiles("demo")
@Testcontainers
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PresentationLifecyclePostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName(PresentationLifecycleScenarios.DATABASE).withStartupTimeout(java.time.Duration.ofMinutes(3));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->DB.getJdbcUrl().split("\\?")[0]);
        r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ListingOperations listings;
    @Autowired ReservationOperations requests;
    @Autowired HandoverOperations handovers;
    @Autowired ExpirationOperations expiration;
    @Autowired DailyDistributionOperations distribution;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    PresentationSeedMaintenance maintenance;
    PresentationSeedMaintenance.LifecycleOperations operations;
    MockEnvironment env;
    final ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup() {
        jdbc.execute("truncate activity,handovers,requests,listings,users"); // This class's disposable container only.
        env=new MockEnvironment().withProperty("foodbridge.presentation.maintenance-enabled","true")
                .withProperty("foodbridge.presentation.lifecycle-disposable-enabled","true");
        env.setActiveProfiles("demo");
        maintenance=new PresentationSeedMaintenance(jdbc,env);
        operations=new PresentationSeedMaintenance.LifecycleOperations(listings,handovers,expiration,distribution);
    }
    UUID listing(int n) {return DemoSeedDataset.load().listingIds().get(n-1);}
    UUID request(int n) {return PresentationLifecycleScenarios.requestId(n,"primary");}
    Actor recipient(int n) {return new Actor(DemoSeed.id("recipient"+n),Actor.Role.RECIPIENT);}
    Actor donor(int n) {return new Actor(DemoSeed.uuid(DemoSeedDataset.load().root().get("listings").get(n-1),"donor_id"),Actor.Role.DONOR);}
    void apply(String token) {new TransactionTemplate(manager).executeWithoutResult(s->maintenance.applyLifecycle(token,operations));}
    String install() {
        var preview=maintenance.lifecyclePreview();assertTrue(preview.blockers().isEmpty(),preview.blockers().toString());
        apply(preview.approvalToken());return preview.approvalToken();
    }
    int count(String sql) {return jdbc.queryForObject(sql,Integer.class);}
    String snapshot() {
        return jdbc.queryForObject("""
                select jsonb_build_object('users',(select jsonb_agg(to_jsonb(u) order by id) from users u),
                'listings',(select jsonb_agg(to_jsonb(l) order by id) from listings l),
                'requests',(select jsonb_agg(to_jsonb(r) order by id) from requests r),
                'handovers',(select jsonb_agg(to_jsonb(h) order by id) from handovers h),
                'activity',(select jsonb_agg(to_jsonb(a) order by id) from activity a))::text
                """,String.class);
    }
    Map<String,Integer> counts(String table) {
        var result=new TreeMap<String,Integer>();
        jdbc.query("select status,count(*) as n from "+table+" group by status",rs->{result.put(rs.getString("status"),rs.getInt("n"));});
        return result;
    }
    @Test void freshInstallUsesExactlyTheExistingDatasetAndReachableStates() throws Exception {
        install();
        assertEquals(new HashSet<>(DemoSeedDataset.load().listingIds()),new HashSet<>(jdbc.queryForList("select id from listings",UUID.class)));
        assertEquals(40,count("select count(*) from listings"));
        assertEquals(36,count("select count(*) from listings where image_url like 'https://res.cloudinary.com/%'"));
        for(JsonNode expected:DemoSeedDataset.load().root().get("listings")) {
            var row=listings.get(DemoSeed.uuid(expected,"id"));
            assertEquals(expected.path("title").asText(),row.title());assertEquals(DemoSeed.uuid(expected,"donor_id"),row.donorId());
            assertEquals(0,expected.path("quantity").decimalValue().compareTo(row.quantity()));
            assertEquals(expected.path("unit").asText(),row.unit());
            assertEquals(expected.path("image_url").isNull()?null:expected.path("image_url").asText(),row.imageUrl());
        }
        assertEquals(Map.of("AVAILABLE",32,"RESERVED",1,"COLLECTED",1,"EXPIRED",4,"DELETED",2),counts("listings"));
        assertEquals(Map.of("PENDING",1,"APPROVED",2,"DECLINED",3,"REVERSED",1,"CANCELLED",1,"EXPIRED",3),counts("requests"));
        assertEquals(Map.of("AWAITING_COLLECTION",1,"COLLECTED",1,"REVERSED",1,"EXPIRED",1),counts("handovers"));
        assertEquals(70,count("select count(*) from activity"));
        assertEquals(0,count("select count(*) from listings where status='REVERSED'"));
        assertEquals(0,count("select count(*) from requests r join listings l on l.id=r.listing_id where r.created_at>=l.collection_deadline"));
        assertEquals(0,count("select count(*) from requests r join listings l on l.id=r.listing_id where l.created_at>r.created_at"));
        assertEquals(0,count("select count(*) from handovers h join requests r on r.id=h.request_id join listings l on l.id=r.listing_id where h.status='AWAITING_COLLECTION' and (r.status<>'APPROVED' or l.status<>'RESERVED')"));
        assertEquals(2,count("select count(*) from requests where listing_id='"+listing(4)+"' and status='DECLINED'"));
        assertEquals("CANCELLED",requests.get(recipient(1),request(7)).status());
    }
    @Test void twoPendingExpirationsHaveRealProcessingEvidenceAndHistoricalProvenance() throws Exception {
        install();
        var evidence=json.createArrayNode();
        for(int n:List.of(9,10)) {
            var r=requests.get(recipient(n==9?2:3),request(n));var l=listings.get(listing(n));
            assertTrue(r.createdAt().isBefore(l.collectionDeadline()));assertTrue(!r.expiredAt().isBefore(l.collectionDeadline()));
            assertEquals(r.expiredAt(),l.expiredAt());assertEquals(r.expiredAt(),r.decidedAt());
            assertNotEquals(l.collectionDeadline(),l.expiredAt());
            JsonNode event=json.readTree(jdbc.queryForObject("select details from activity where id=?",String.class,PresentationLifecycleScenarios.activityId(n,"expire")));
            assertEquals("PENDING",event.path("requests").get(0).path("previous_status").asText());
            assertEquals("DOMAIN_OPERATION_EXECUTED",event.path("presentation_fixture").path("origin").asText());
            assertEquals(r.expiredAt(),jdbc.queryForObject("select created_at from activity where id=?",Timestamp.class,PresentationLifecycleScenarios.activityId(n,"expire")).toInstant());
            assertEquals("HISTORICAL_FIXTURE_NOT_COMMAND_EXECUTION",json.readTree(jdbc.queryForObject("select details from activity where id=?",String.class,
                    PresentationLifecycleScenarios.activityId(n,"request-created-primary"))).path("origin").asText());
            var item=evidence.addObject();item.put("listingId",l.id().toString());item.put("requestId",r.id().toString());
            item.put("requestCreatedAt",r.createdAt().toString());item.put("collectionDeadline",l.collectionDeadline().toString());
            item.put("expiredAt",r.expiredAt().toString());item.set("expirationActivity",event);
        }
        Files.writeString(Path.of("target/presentation-lifecycle-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    @Test void reservationExpirationAndArchiveRetainApprovalAndProcessingMetadata() {
        install();
        var approved=requests.get(recipient(1),request(11));var l=listings.get(listing(11));
        assertEquals("EXPIRED",approved.status());assertTrue(approved.createdAt().isBefore(approved.decidedAt()));
        assertTrue(approved.decidedAt().isBefore(l.collectionDeadline()));assertTrue(l.collectionDeadline().isBefore(approved.expiredAt()));
        assertEquals(1,count("select count(*) from handovers h join requests r on r.id=h.request_id where r.id='"+request(11)+"' and h.status='EXPIRED' and h.expired_at=r.expired_at and h.completed_at is null"));
        var archived=listings.get(listing(13));assertEquals("DELETED",archived.status());assertNotNull(archived.expiredAt());
        assertTrue(!archived.updatedAt().isBefore(archived.expiredAt()));
        assertEquals(archived.expiredAt(),jdbc.queryForObject("select created_at from activity where id=?",Timestamp.class,PresentationLifecycleScenarios.activityId(13,"expire")).toInstant());
        assertEquals("EXPIRED",jdbc.queryForObject("select details::jsonb->>'previous_status' from activity where id=?",String.class,PresentationLifecycleScenarios.activityId(13,"archive")));
    }
    @Test void reversalAndDashboardMatchMain() throws Exception {
        install();
        assertEquals("AVAILABLE",listings.get(listing(6)).status());assertEquals("REVERSED",requests.get(recipient(3),request(6)).status());
        var h=jdbc.queryForMap("select * from handovers where id=?",PresentationLifecycleScenarios.handoverId(6));
        assertTrue(((Timestamp)h.get("completed_at")).before((Timestamp)h.get("reversed_at")));
        Instant approval=jdbc.queryForObject("select created_at from activity where id=?",Timestamp.class,PresentationLifecycleScenarios.activityId(6,"decision-primary")).toInstant();
        assertTrue(approval.isBefore(((Timestamp)h.get("completed_at")).toInstant()));
        assertFalse(requests.get(recipient(3),request(6)).decidedAt().isBefore(((Timestamp)h.get("reversed_at")).toInstant()));
        var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/dashboard")
                .header("Authorization",adminToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var dashboard=json.readTree(response.getResponse().getContentAsString());
        for(var e:Map.of("totalUsers",6,"totalDonors",2,"totalRecipients",3,"totalListings",38,"availableListings",32,
                "reservedListings",1,"collectedListings",1,"expiredListings",4,"pendingRequests",1,"completedHandovers",1).entrySet())
            assertEquals(e.getValue(),dashboard.path(e.getKey()).asInt(),e.getKey());
        assertEquals(11,dashboard.path("totalRequests").asInt());assertEquals(18,dashboard.path("estimatedPortionsSaved").asInt());
    }
    String adminToken() throws Exception {
        var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                .contentType("application/json").content("{\"email\":\"admin@demo.foodbridge.invalid\",\"password\":\"admin123\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        return "Bearer "+json.readTree(response.getResponse().getContentAsString()).path("token").asText();
    }
    @Test void realFairnessAndAutoDeclineStillApply() {
        install();
        UUID extra=listings.create(donor(1),new ListingOperations.CreateListing("Team food","Later interaction","OTHER",BigDecimal.ONE,"ITEMS",null,"Lagos",Instant.now().plusSeconds(86400))).id();
        UUID served=requests.request(recipient(1),extra,"served-again").id();
        assertEquals("DAILY_DISTRIBUTION_PRIORITY",assertThrows(RuleViolation.class,()->requests.approve(donor(1),served)).code());
        UUID waiting=requests.request(recipient(3),extra,"waiting-first").id();requests.approve(donor(1),waiting);
        assertEquals("DECLINED",requests.get(recipient(1),served).status());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                "insert into requests(id,listing_id,requester_id,status,decided_at) values (?,?,?,'APPROVED',clock_timestamp())",UUID.randomUUID(),extra,recipient(2).id()));
    }
    @Test void repeatInstallAndReadOnlyPreviewPreserveEveryRecordAndAnchor() {
        String token=install(), before=snapshot();
        var tx=new TransactionTemplate(manager);tx.setReadOnly(true);
        tx.executeWithoutResult(s->assertTrue(maintenance.lifecyclePreview().blockers().isEmpty()));
        apply(token);apply(maintenance.lifecyclePreview().approvalToken());assertEquals(before,snapshot());
    }
    @Test void operationalProgressAndAdditionalRecordsAreReportedAndNeverReset() {
        String token=install();
        requests.decline(donor(2),request(2));
        handovers.reverseByDonorOrAdmin(PresentationLifecycleScenarios.handoverId(5),donor(5).id(),false);
        listings.create(donor(1),new ListingOperations.CreateListing("Team food","Not a fixture","OTHER",BigDecimal.ONE,"ITEMS",null,"Lagos",Instant.now().plusSeconds(86400)));
        String before=snapshot();var preview=maintenance.lifecyclePreview();assertTrue(preview.blockers().isEmpty(),preview.blockers().toString());
        assertFalse(preview.affectedRecords().path("progress_or_extra_records").isEmpty());
        apply(token);assertEquals(before,snapshot());
    }
    @ParameterizedTest @ValueSource(strings={"title","donor_id","image_url","collection_deadline","activity","manifest","missing-request"})
    void modifiedOrMissingScenarioRecordsBlockWithoutOverwrite(String modification) throws Exception {
        String token=install();
        switch(modification) {
            case "title" -> jdbc.update("update listings set title='User edit' where id=?",listing(1));
            case "donor_id" -> jdbc.update("update listings set donor_id=? where id=?",DemoSeed.id("donor2"),listing(1));
            case "image_url" -> jdbc.update("update listings set image_url='https://example.invalid/user-edit.jpg' where id=?",listing(1));
            case "collection_deadline" -> jdbc.update("update listings set collection_deadline=collection_deadline+interval '1 day' where id=?",listing(1));
            case "activity" -> jdbc.update("update activity set details='{}' where id=?",PresentationLifecycleScenarios.activityId(1,"listing-created"));
            case "manifest" -> {
                var details=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(jdbc.queryForObject("select details from activity where id=?",String.class,PresentationLifecycleScenarios.MANIFEST));
                details.put("anchor","2000-01-01T00:00:00Z");
                jdbc.update("update activity set details=? where id=?",details.toString(),PresentationLifecycleScenarios.MANIFEST);
            }
            default -> jdbc.update("delete from requests where id=?",request(2));
        }
        String before=snapshot();assertFalse(maintenance.lifecyclePreview().blockers().isEmpty());assertThrows(IllegalStateException.class,()->apply(token));
        assertEquals(before,snapshot());
    }
    @Test void existingInstallationWithoutManifestIsOnlyAReadOnlyPlan() {
        install();jdbc.update("delete from activity where id=?",PresentationLifecycleScenarios.MANIFEST);
        String before=snapshot();var preview=maintenance.lifecyclePreview();
        assertEquals("EXISTING_INSTALLATION_READ_ONLY",preview.affectedRecords().path("disposition").asText());
        assertThrows(IllegalStateException.class,()->apply(preview.approvalToken()));
        assertNotNull(maintenance.preview().affectedRecords().get("lifecycle_scenario_plan"));assertEquals(before,snapshot());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","production","no-transaction","stale-token"})
    void gatesRejectBeforeWriting(String gate) {
        var token=maintenance.lifecyclePreview().approvalToken();
        switch(gate) {
            case "disabled" -> env.setProperty("foodbridge.presentation.lifecycle-disposable-enabled","false");
            case "production" -> env.setActiveProfiles("demo","production");
            case "no-transaction" -> {assertThrows(IllegalStateException.class,()->maintenance.applyLifecycle(maintenance.lifecyclePreview().approvalToken(),operations));return;}
            default -> token="0".repeat(64);
        }
        String checked=token;assertThrows(IllegalStateException.class,()->apply(checked));assertEquals(0,count("select count(*) from users"));
    }
    @ParameterizedTest @ValueSource(strings={"listings","requests","activity"})
    void lateInstallationFailureRollsBackAllDomainAndFixtureWrites(String table) {
        jdbc.execute("alter table "+table+" add constraint reject_scenario_test check("+switch(table){
            case "listings" -> "status<>'DELETED'"; case "requests" -> "status<>'EXPIRED'";default -> "type<>'REVERSED'";}+")");
        String token=maintenance.lifecyclePreview().approvalToken(); // Review the deliberately rejecting test schema.
        try {assertThrows(RuntimeException.class,()->apply(token));for(String t:List.of("users","listings","requests","handovers","activity"))assertEquals(0,count("select count(*) from "+t));}
        finally {jdbc.execute("alter table "+table+" drop constraint reject_scenario_test");}
        install();assertEquals(40,count("select count(*) from listings"));
    }
    @ParameterizedTest @ValueSource(strings={"jdbc:postgresql://example.invalid/foodbridge_presentation_disposable",
            "jdbc:postgresql://localhost/neondb","jdbc:postgresql://localhost/foodbridge_presentation_disposable?password=secret"})
    void sharedOrCredentialEmbeddedTargetsAreRejected(String url) {
        assertThrows(IllegalArgumentException.class,()->PresentationLifecycleScenarios.validateTarget(url));
    }

}

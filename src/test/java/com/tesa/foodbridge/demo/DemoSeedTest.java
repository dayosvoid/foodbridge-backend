package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No application context, network or real database. JDBC mutations are mocked. */
class DemoSeedTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MockEnvironment env = new MockEnvironment();
    private final Map<String,ArrayNode> rows = new LinkedHashMap<>();
    private DemoSeed seed;

    @BeforeEach void setup() {
        env.setActiveProfiles("demo");
        env.setProperty("foodbridge.demo.seed-enabled","true");
        for (String table : List.of("legacy_listings","requests","handovers","activity","replacement_listings","users","database"))
            rows.put(table,json.createArrayNode());
        rows.get("database").addObject().put("database","disposable-unit-test");
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            String key = sql.contains("current_database()") ? "database"
                    : sql.contains("from handovers") ? "handovers"
                    : sql.contains("from requests") ? "requests"
                    : sql.contains("from activity") ? "activity"
                    : sql.contains("from users") ? "users"
                    : sql.contains(DemoSeed.id("replacement-v1-listing-01").toString()) ? "replacement_listings" : "legacy_listings";
            return rows.get(key).toString();
        });
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenAnswer(call ->
                ((String)call.getArgument(0)).contains(DemoSeed.id("replacement-v1-listing-01").toString()) ? 40 : 0);
        seed = new DemoSeed(jdbc,env);
    }
    @AfterEach void clearTransaction() { TransactionSynchronizationManager.setActualTransactionActive(false); }

    @Test void packagedDatasetHasFortyStableRecordsAndAllApprovedImages() throws Exception {
        var data = DemoSeedDataset.load();
        assertEquals(40,data.listingIds().size());
        assertEquals(40,new HashSet<>(data.listingIds()).size());
        var approved = json.readTree(java.nio.file.Path.of("seed-input/replacement-demo-dataset.json").toFile());
        assertEquals(approved.get("listings"),data.root().get("listings"));
        assertEquals(approved.get("donors"),data.root().get("donors"));
        assertEquals("487c47b3-65e8-3ca1-bccb-7ee2a44172d6",DemoSeed.id("donor1").toString());
    }
    @Test void everyPackagedListingHasValidDemoDonorAndExpectedCloudinaryFormat() {
        var data=DemoSeedDataset.load();
        var donorIds=new HashSet<String>();
        data.root().get("donors").forEach(donor->{
            assertEquals("DONOR",donor.path("role").asText());
            donorIds.add(donor.path("id").asText());
        });
        int pictured=0,unpictured=0;
        var urls=new HashSet<String>();
        var legacyIds=new HashSet<UUID>();
        for(int i=1;i<=12;i++) legacyIds.add(DemoSeed.id("listing"+i));
        for(JsonNode listing:data.root().get("listings")) {
            assertTrue(donorIds.contains(listing.path("donor_id").asText()));
            assertFalse(legacyIds.contains(DemoSeed.uuid(listing,"id")));
            if(listing.get("image_url").isNull()) {unpictured++;continue;}
            String url=listing.path("image_url").asText();
            assertFalse(url.isBlank());
            assertTrue(url.matches("https://res[.]cloudinary[.]com/qjcfxycq/image/upload/v[0-9]+/[A-Za-z0-9_-]+[.](jpg|webp)"),url);
            assertTrue(urls.add(url),"Duplicate URL: "+url);
            pictured++;
        }
        assertTrue(pictured>=35);
        assertEquals(36,pictured);
        assertEquals(4,unpictured);
    }
    @Test void allFortyDeadlinesProtectThePresentationFromEarlySeeding() {
        var data=DemoSeedDataset.load();
        var lagos=java.time.ZoneId.of("Africa/Lagos");
        assertEquals(java.time.ZonedDateTime.of(2026,11,3,10,0,0,0,lagos).toInstant(),DemoSeedDataset.PRESENTATION);
        assertEquals(java.time.ZonedDateTime.of(2026,11,5,18,0,0,0,lagos).toInstant(),DemoSeedDataset.PRESENTATION_DEADLINE_FLOOR);
        for(var epoch:List.of(java.time.Instant.parse("2026-11-01T00:00:00Z"),DemoSeedDataset.PRESENTATION))
            for(JsonNode listing:data.root().get("listings")) {
                var deadline=DemoSeedDataset.collectionDeadline(listing,epoch);
                assertFalse(deadline.isBefore(DemoSeedDataset.PRESENTATION_DEADLINE_FLOOR));
                assertTrue(deadline.isAfter(DemoSeedDataset.PRESENTATION));
                assertTrue(deadline.isAfter(epoch));
            }
    }
    @Test void lateSeedingRetainsRelativeDeadlinesAndDoesNotCreateExpiredRows() {
        var data=DemoSeedDataset.load();
        var epoch=java.time.Instant.parse("2026-11-06T00:00:00Z");
        for(JsonNode listing:data.root().get("listings"))
            assertEquals(epoch.plusSeconds(listing.path("collection_deadline").path("offset_seconds").asLong()),
                DemoSeedDataset.collectionDeadline(listing,epoch));
    }
    @Test void missingPresentationFloorIsRejected() {
        var data=DemoSeedDataset.load();
        ((ObjectNode)data.root().get("listings").get(0).get("collection_deadline")).remove("not_before");
        assertThrows(IllegalStateException.class,data::validate);
    }

    @Test void springRegistrationExcludesProductionEvenWhenDestructiveFlagsAreSet() {
        for(String profiles:List.of("prod","production","demo,prod","demo,production")) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(DemoSeed.class)
                .withBean(JdbcTemplate.class,()->jdbc)
                .withPropertyValues("spring.profiles.active="+profiles,"foodbridge.demo.seed-enabled=true",
                    "foodbridge.demo.seed-mode=replace","foodbridge.demo.allow-legacy-cleanup=true",
                    "foodbridge.demo.approved-preview=not-authorization")
                .run(context->{assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(DemoSeed.class).size());});
        }
        assertOnlyBeanInitialization();
    }
    @Test void springRegistrationRequiresBothDemoProfileAndEnabledFlag() {
        for(String profiles:List.of("","dev","demo")) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(DemoSeed.class).withBean(JdbcTemplate.class,()->jdbc)
                .withPropertyValues("spring.profiles.active="+profiles,"foodbridge.demo.seed-enabled=false")
                .run(context->{assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(DemoSeed.class).size());});
        }
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(DemoSeed.class).withBean(JdbcTemplate.class,()->jdbc)
            .withPropertyValues("spring.profiles.active=demo","foodbridge.demo.seed-enabled=true")
            .run(context->{assertNull(context.getStartupFailure());assertEquals(1,context.getBeansOfType(DemoSeed.class).size());});
        assertOnlyBeanInitialization();
    }
    @Test void defaultRunOnlyPreviewsEvenWithLegacyFixtures() {
        legacyFixtures();
        var preview = seed.preview();
        assertTrue(preview.blockers().isEmpty());
        assertEquals(12,preview.affectedRecords().get("legacy_listings").size());
        assertEquals(8,preview.affectedRecords().get("requests").size());
        assertEquals(3,preview.affectedRecords().get("handovers").size());
        seed.run(new DefaultApplicationArguments());
        assertNoUpdates();
        verify(jdbc,never()).execute(anyString());
        assertEquals(preview.approvalToken(),seed.preview().approvalToken());
        assertFalse(preview.affectedRecords().get("users").toString().contains("password_hash"));
    }
    @Test void productionProfilesRejectDirectInvocation() {
        env.setProperty("foodbridge.demo.seed-mode","replace");
        env.setProperty("foodbridge.demo.allow-legacy-cleanup","true");
        env.setProperty("foodbridge.demo.approved-preview","not-authorization");
        for (String profile : List.of("prod","production")) {
            env.setActiveProfiles("demo",profile);
            assertThrows(IllegalStateException.class,() -> seed.run(new DefaultApplicationArguments()));
        }
        assertNoUpdates();
        verifyNoInteractions(jdbc);
    }
    @Test void disabledFlagRejectsDirectInvocation() {
        env.setProperty("foodbridge.demo.seed-enabled","false");
        assertThrows(IllegalStateException.class,() -> seed.run(new DefaultApplicationArguments()));
        verifyNoInteractions(jdbc);
    }
    @Test void startupReplacementIsDisabledEvenWithApproval() {
        env.setProperty("foodbridge.demo.seed-mode","replace");
        env.setProperty("foodbridge.demo.approved-preview",seed.preview().approvalToken());
        assertTrue(assertThrows(IllegalStateException.class,()->seed.run(new DefaultApplicationArguments())).getMessage().contains("Startup replacement is disabled"));
        assertNoUpdates();
    }
    @Test void cleanupFlagsCannotEnableStartupMutation() {
        legacyFixtures(); approve();
        env.setProperty("foodbridge.demo.allow-legacy-cleanup","true");
        assertThrows(IllegalStateException.class,()->seed.run(new DefaultApplicationArguments()));
        assertNoUpdates();
    }
    @Test void rowChangesInvalidateThePreviewDigest() {
        legacyFixtures();
        String before=seed.preview().approvalToken();
        ((ObjectNode)rows.get("legacy_listings").get(0)).put("updated_at","changed-since-approval");
        assertNotEquals(before,seed.preview().approvalToken());
        assertNoUpdates();
    }
    @Test void unknownRequestBlocksCleanupEvenWhenTokenIsApproved() {
        legacyFixtures();
        rows.get("requests").addObject().put("id",UUID.randomUUID().toString()).put("listing_id",DemoSeed.id("listing1").toString());
        assertBlocked("Unexpected or changed request");
    }
    @Test void unknownCollectionBlocksCleanup() {
        legacyFixtures();
        rows.get("handovers").addObject().put("id",UUID.randomUUID().toString()).put("request_id",DemoSeed.id("request2-1").toString());
        assertBlocked("Unexpected or changed handover");
    }
    @Test void activityIsPreservedAndBlocksCleanup() {
        legacyFixtures(); rows.get("activity").addObject().put("id",UUID.randomUUID().toString());
        assertBlocked("Activity references legacy listing");
    }
    @Test void changedLegacyListingAndReboundFixtureRequestAreRejected() {
        legacyFixtures();
        ((ObjectNode)rows.get("legacy_listings").get(0)).put("title","A real user's donation");
        ((ObjectNode)rows.get("requests").get(0)).put("listing_id",UUID.randomUUID().toString());
        assertBlocked("Legacy listing differs");
        assertTrue(seed.preview().blockers().stream().anyMatch(value -> value.contains("Unexpected or changed request")));
    }
    @Test void conflictingDonorEmailNeverRepurposesUser() {
        rows.get("users").addObject().put("id",UUID.randomUUID().toString())
                .put("email","donor1@demo.foodbridge.invalid").put("role","DONOR").put("display_name","Someone else");
        assertBlocked("Donor identity conflict");
    }
    @Test void startupCannotProvisionAccounts() {
        approve();
        assertThrows(IllegalStateException.class,()->seed.run(new DefaultApplicationArguments()));
        assertNoUpdates();
    }
    @Test void startupNeverDeletesLegacyHistory() {
        legacyFixtures(); approve(); env.setProperty("foodbridge.demo.allow-legacy-cleanup","true");
        assertThrows(IllegalStateException.class,()->seed.run(new DefaultApplicationArguments()));
        assertNoUpdates();
        assertEquals(12,rows.get("legacy_listings").size());
        assertEquals(8,rows.get("requests").size());
        assertEquals(3,rows.get("handovers").size());
    }
    @Test void freshDatabaseStillCannotBeMutatedByStartup() {
        env.setProperty("DEMO_PASSWORD_HASH","unit-test-only-disabled-hash"); approve();
        assertThrows(IllegalStateException.class,()->seed.run(new DefaultApplicationArguments()));
        assertNoUpdates();
    }
    @Test void startupCannotResetInstalledData() {
        var data=DemoSeedDataset.load();
        data.root().get("donors").forEach(donor->rows.get("users").add(donor.deepCopy()));
        data.root().get("listings").forEach(listing->rows.get("replacement_listings").add(listing.deepCopy()));
        ((ObjectNode)rows.get("replacement_listings").get(0)).put("status","RESERVED");
        approve();
        assertThrows(IllegalStateException.class,()->seed.run(new DefaultApplicationArguments()));
        assertNoUpdates();
    }
    @Test void partialOrCollidingReplacementDatasetAborts() {
        rows.get("replacement_listings").add(DemoSeedDataset.load().root().get("listings").get(0).deepCopy());
        ((ObjectNode)rows.get("replacement_listings").get(0)).put("donor_id",UUID.randomUUID().toString());
        assertBlocked("Replacement ID contains different content");
        assertTrue(seed.preview().blockers().stream().anyMatch(value -> value.contains("Partial replacement")));
    }
    @Test void changedRequestDecisionBlocksApprovedCleanup() {
        legacyFixtures();
        ((ObjectNode)rows.get("requests").get(0)).put("status","DECLINED");
        assertBlocked("Unexpected or changed request");
    }
    @Test void changedHandoverStateBlocksApprovedCleanup() {
        legacyFixtures();
        ((ObjectNode)rows.get("handovers").get(0)).put("status","COLLECTED");
        assertBlocked("Unexpected or changed handover");
    }
    @Test void unexpectedRecipientIdentityBlocksApprovedCleanup() {
        legacyFixtures();
        ((ObjectNode)rows.get("users").get(2)).put("email","someone@example.invalid");
        assertBlocked("Fixture user identity mismatch");
    }
    private void approve() {
        env.setProperty("foodbridge.demo.seed-mode","replace");
        env.setProperty("foodbridge.demo.approved-preview",seed.preview().approvalToken());
        // Simulated transaction for mocked JDBC only. Integration tests use Spring's real transaction manager.
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    private void assertBlocked(String message) {
        approve(); env.setProperty("foodbridge.demo.allow-legacy-cleanup","true");
        assertTrue(seed.preview().blockers().stream().anyMatch(value -> value.contains(message)));
        assertNoUpdates();
    }
    private void assertNoUpdates() {
        assertTrue(mockingDetails(jdbc).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("update")));
    }
    private void assertOnlyBeanInitialization() {
        // Spring initializes the mocked JdbcTemplate bean; that callback executes no SQL.
        assertTrue(mockingDetails(jdbc).getInvocations().stream()
            .allMatch(i->i.getMethod().getName().equals("afterPropertiesSet")));
    }
    private void legacyFixtures() {
        for (String name : List.of("donor1","donor2","recipient1","recipient2","recipient3","admin")) {
            rows.get("users").addObject().put("id",DemoSeed.id(name).toString()).put("email",name+"@demo.foodbridge.invalid")
                    .put("display_name","SIMULATED "+name).put("role",name.startsWith("donor")?"DONOR":name.startsWith("recipient")?"RECIPIENT":"ADMIN");
        }
        for (int i=1;i<=12;i++) {
            String status=switch(i) { case 5 -> "RESERVED"; case 6 -> "COLLECTED"; case 7 -> "REVERSED"; case 11,12 -> "EXPIRED"; default -> "AVAILABLE"; };
            ObjectNode listing=rows.get("legacy_listings").addObject().put("id",DemoSeed.id("listing"+i).toString())
                    .put("donor_id",DemoSeed.id(i%2==0?"donor2":"donor1").toString())
                    .put("title","SIMULATED "+(i==10?"Community lunch surplus with assorted vegetables and rice for multiple recipient organizations collecting at the designated afternoon pickup point ":"Meal donation ")+i)
                    .put("description","Fictional demonstration listing; no real food is offered.")
                    .put("category","PREPARED_MEALS").put("quantity",20).put("unit","PORTIONS")
                    .put("pickup_area","SIMULATED Lagos pickup").put("status",status);
            if (i==9) listing.putNull("image_url"); else listing.put("image_url","https://example.invalid/simulated-food.jpg");
            if (i>=2 && i<=7) {
                for (int r=1;r<=(i==3?3:1);r++) rows.get("requests").addObject()
                        .put("id",DemoSeed.id("request"+i+"-"+r).toString()).put("listing_id",DemoSeed.id("listing"+i).toString())
                        .put("requester_id",DemoSeed.id("recipient"+r).toString()).put("status",i>=5?"APPROVED":"PENDING")
                        .put("idempotency_key","simulated-"+i+"-"+r);
                if (i>=5) rows.get("handovers").addObject().put("id",DemoSeed.id("handover"+i).toString())
                        .put("request_id",DemoSeed.id("request"+i+"-1").toString()).put("portions_estimate",20)
                        .put("status",i==5?"AWAITING_COLLECTION":i==6?"COLLECTED":"REVERSED");
            }
        }
    }
}

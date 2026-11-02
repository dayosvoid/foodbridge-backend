package com.tesa.foodbridge;

import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.reservation.api.ReservationOperations;
import com.tesa.foodbridge.shared.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("demo")
@Testcontainers
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PostgresRulesTest {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("foodbridge.demo.seed-enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ListingOperations listings;

    @Autowired
    ReservationOperations requests;

    @Autowired
    org.springframework.test.web.servlet.MockMvc http;

    String bearer(String login) {
        String name = login.equals("admin1") ? "admin" : login;
        String password = switch (login) {
            case "donor1", "donor2" -> "donor123";
            case "recipient1", "recipient2", "recipient3" -> "recipient123";
            default -> "admin123";
        };

        try {
            var response = http.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/auth/login")
                            .contentType("application/json")
                            .content("{\"email\":\"" + name
                                    + "@demo.foodbridge.invalid\",\"password\":\""
                                    + password + "\"}")
            ).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isOk()
            ).andReturn();

            return "Bearer " + new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.getResponse().getContentAsString())
                    .get("token").asText();

        } catch (Exception e) {
            throw new AssertionError("Test login failed", e);
        }
    }

    Actor donor;
    Actor recipient;

    @BeforeEach
    void setup() {
        jdbc.execute("truncate activity,handovers,requests,listings,users");
        donor = user(Actor.Role.DONOR);
        recipient = user(Actor.Role.RECIPIENT);
    }


    Actor user(Actor.Role role) {

        UUID id = UUID.randomUUID();

        jdbc.update("""
                insert into users(
                    id,
                    email,
                    password_hash,
                    display_name,
                    role
                )
                values (?, ?, ?, ?, ?)
                """,
                id,
                id + "@example.invalid",
                "disabled",
                "Simulated",
                role.name()
        );

        return new Actor(id, role);
    }

    UUID listing() {

        return listings.create(
                donor,
                command(BigDecimal.TEN)
        ).id();
    }

    ListingOperations.CreateListing command(
            BigDecimal quantity) {

        return new ListingOperations.CreateListing(
                "Simulated food",
                "Test listing",
                "BAKERY",
                quantity,
                "ITEMS",
                null,
                "Lagos",
                Instant.now().plusSeconds(3600)
        );
    }

    UUID request(UUID listing) {

        return requests.request(
                recipient,
                listing,
                null
        ).id();
    }

    int count(String sql) {

        return jdbc.queryForObject(sql, Integer.class);
    }

    void code(
            String code,
            org.junit.jupiter.api.function.Executable call) {

        assertEquals(
                code,
                assertThrows(RuleViolation.class, call).code()
        );
    }

    // --------------------------------------------------
    // CONCURRENT APPROVAL TEST
    // --------------------------------------------------

    @Test
    void concurrentApprovalsHaveOneWinnerAndAtomicState()
            throws Exception {

        UUID listing = listing();

        List<UUID> ids = new ArrayList<>();

        for (int i = 0; i < 3; i++) {

            ids.add(
                    requests.request(
                            user(Actor.Role.RECIPIENT),
                            listing,
                            null
                    ).id()
            );
        }

        var results = parallel(ids, id -> {

            try {

                requests.approve(donor, id);
                return "SUCCESS";

            } catch (RuleViolation e) {

                return e.code();
            }
        });

        assertEquals(
                1,
                Collections.frequency(results, "SUCCESS")
        );

        assertEquals(
                2,
                Collections.frequency(
                        results,
                        "LISTING_ALREADY_RESERVED"
                )
        );

        assertEquals(
                1,
                count("select count(*) from requests where status='APPROVED'")
        );

        assertEquals(
                0,
                count("""
                    select count(*)
                    from requests
                    where status='PENDING'
                    """)
        );

        assertEquals(
                2,
                count("""
                    select count(*)
                    from requests
                    where status='DECLINED'
                      and decided_at is not null
                    """)
        );

        assertEquals(
                1,
                count("""
                    select count(*)
                    from handovers
                    where status='AWAITING_COLLECTION'
                    """)
        );

        assertEquals(
                1,
                count("""
                    select count(*)
                    from activity
                    where type='REQUEST_APPROVED'
                    """)
        );

        assertEquals(
                1,
                count("""
                    select count(*)
                    from listings
                    where status='RESERVED'
                    """)
        );

        System.out.println(
                "SLIDE4 SERVICE: approvals=1 conflicts=2 handovers=1 atomic state verified"
        );
    }

    // --------------------------------------------------
    // DAILY FAIR DISTRIBUTION TEST
    // --------------------------------------------------

    @Test
    void dailyFirstRoundServesUnservedRecipientsBeforeSecondApproval() {

        Actor recipientB = user(Actor.Role.RECIPIENT);
        Actor recipientC = user(Actor.Role.RECIPIENT);

        UUID firstListing = listing();
        UUID secondListing = listing();
        UUID recipientBListing = listing();
        UUID recipientCListing = listing();

        UUID firstRequest =
                requests.request(recipient, firstListing, null).id();

        UUID secondRequest =
                requests.request(recipient, secondListing, null).id();

        UUID requestB =
                requests.request(recipientB, recipientBListing, null).id();

        UUID requestC =
                requests.request(recipientC, recipientCListing, null).id();

        // First recipient receives their first allocation.
        requests.approve(donor, firstRequest);

        // The same recipient cannot receive a second allocation yet.
        code(
                "DAILY_DISTRIBUTION_PRIORITY",
                () -> requests.approve(donor, secondRequest)
        );

        // Other recipients receive their first allocations.
        requests.approve(donor, requestB);
        requests.approve(donor, requestC);

        // The second round now opens.
        requests.approve(donor, secondRequest);

        assertEquals(
                4,
                count("""
                    select count(*)
                    from requests
                    where status='APPROVED'
                    """)
        );
    }

    // --------------------------------------------------
    // DUPLICATE APPROVAL TEST
    // --------------------------------------------------

    @Test
    void duplicateApprovalIsConflict() {

        UUID id = request(listing());

        requests.approve(donor, id);

        code(
                "LISTING_ALREADY_RESERVED",
                () -> requests.approve(donor, id)
        );

        assertEquals(
                1,
                count("select count(*) from handovers")
        );
    }

    // --------------------------------------------------
    // EXPIRED LISTING TEST
    // --------------------------------------------------

    @Test
    void expiredListingsRejectRequestsAndApprovals() {

        UUID listing = listing();
        UUID id = request(listing);

        jdbc.update("""
                update listings
                set collection_deadline =
                    clock_timestamp() - interval '1 second'
                where id = ?
                """,
                listing
        );

        code(
                "LISTING_EXPIRED",
                () -> request(listing)
        );

        code(
                "LISTING_EXPIRED",
                () -> requests.approve(donor, id)
        );

        assertEquals(
                0,
                count("select count(*) from handovers")
        );
    }

    // --------------------------------------------------
    // APPROVAL DEADLINE LOCK TEST
    // --------------------------------------------------

    @Test
    void approvalChecksDeadlineAfterWaitingForListingLock()
            throws Exception {

        UUID listing = listing();
        UUID id = request(listing);

        try (var conn = DB.createConnection("")) {

            conn.setAutoCommit(false);

            try (var stmt = conn.prepareStatement("""
                    update listings
                    set collection_deadline =
                        clock_timestamp() + interval '1 second'
                    where id = ?
                    """)) {

                stmt.setObject(1, listing);
                stmt.executeUpdate();
            }

            try (var pool = Executors.newSingleThreadExecutor()) {

                var task = pool.submit(() -> {

                    requests.approve(donor, id);
                    return "SUCCESS";
                });

                // Hold the lock until the deadline has passed.
                try (var stmt = conn.createStatement()) {

                    stmt.execute("select pg_sleep(1.2)");
                }

                conn.commit();

                var failure = assertThrows(
                        ExecutionException.class,
                        () -> task.get(15, TimeUnit.SECONDS)
                );

                assertEquals(
                        "LISTING_EXPIRED",
                        ((RuleViolation) failure.getCause()).code()
                );
            }
        }
    }

    // --------------------------------------------------
    // AUTHORIZATION TEST
    // --------------------------------------------------

    @Test
    void unauthorizedApprovalsAreRejected() {

        UUID id = request(listing());

        Actor stranger = user(Actor.Role.DONOR);

        code(
                "FORBIDDEN",
                () -> requests.approve(stranger, id)
        );

        code(
                "FORBIDDEN",
                () -> requests.approve(recipient, id)
        );

        code(
                "FORBIDDEN",
                () -> requests.approve(
                        new Actor(recipient.id(), Actor.Role.ADMIN),
                        id
                )
        );

        requests.approve(
                user(Actor.Role.ADMIN),
                id
        );
    }

    // --------------------------------------------------
    // INVALID QUANTITY TEST
    // --------------------------------------------------

    @Test
    void invalidQuantitiesRejectedByServiceAndDatabase() {

        code(
                "INVALID_LISTING",
                () -> listings.create(
                        donor,
                        command(BigDecimal.ZERO)
                )
        );

        UUID id = listing();

        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "update listings set quantity=0 where id=?",
                        id
                )
        );
    }

    // --------------------------------------------------
    // MODULE CONTRACT TEST
    // --------------------------------------------------

    @Test
    void moduleContractsEnforceCreationAndReadAccess() {

        code(
                "FORBIDDEN",
                () -> listings.create(
                        recipient,
                        command(BigDecimal.ONE)
                )
        );

        UUID listing = listing();

        code(
                "FORBIDDEN",
                () -> requests.request(donor, listing, null)
        );

        UUID id = request(listing);

        assertEquals(
                id,
                requests.get(donor, id).id()
        );

        Actor other = user(Actor.Role.RECIPIENT);

        code(
                "FORBIDDEN",
                () -> requests.get(other, id)
        );

        code(
                "INVALID_PAGE",
                () -> listings.browse(0, 101)
        );

        assertEquals(
                1,
                listings.browse(0, 20).size()
        );
    }

    // --------------------------------------------------
    // IDEMPOTENCY TEST
    // --------------------------------------------------

    @Test
    void idempotencyIsAtomicAndBoundToActorAndListing()
            throws Exception {

        UUID listing = listing();

        var ids = parallel(
                List.of(1, 2, 3),
                i -> requests.request(
                        recipient,
                        listing,
                        "same-key"
                ).id()
        );

        assertEquals(
                1,
                new HashSet<>(ids).size()
        );

        assertEquals(
                1,
                count("select count(*) from requests")
        );

        Actor other = user(Actor.Role.RECIPIENT);

        code(
                "IDEMPOTENCY_KEY_REUSED",
                () -> requests.request(other, listing, "same-key")
        );

        UUID another = listing();

        code(
                "IDEMPOTENCY_KEY_REUSED",
                () -> requests.request(recipient, another, "same-key")
        );
    }

    // --------------------------------------------------
    // TRANSACTION ROLLBACK TEST
    // --------------------------------------------------

    @Test
    void failedHandoverRollsBackApprovalAndListing() {

        UUID listing = listing();
        UUID id = request(listing);

        // Inject a downstream failure deliberately.
        jdbc.update(
                "insert into handovers(id,request_id) values (?,?)",
                UUID.randomUUID(),
                id
        );

        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> requests.approve(donor, id)
        );

        assertEquals(
                "PENDING",
                requests.get(recipient, id).status()
        );

        assertEquals(
                "AVAILABLE",
                listings.get(listing).status()
        );

        assertEquals(
                0,
                count("""
                    select count(*)
                    from activity
                    where type='REQUEST_APPROVED'
                    """)
        );
    }


    // --------------------------------------------------
    // DEMO SEED TEST
    // --------------------------------------------------

    @Test
    void demoSeedIsRepeatableAndHasRequiredStates() {

        var seed = new com.tesa.foodbridge.demo.DemoSeed(
                jdbc,
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty(
                                "DEMO_PASSWORD_HASH",
                                "disabled-test-hash"
                        )
        );

        seed.run(
                new org.springframework.boot.DefaultApplicationArguments()
        );

        seed.run(
                new org.springframework.boot.DefaultApplicationArguments()
        );

        assertEquals(
                6,
                count("""
                    select count(*)
                    from users
                    where email like '%@demo.foodbridge.invalid'
                    """)
        );

        assertEquals(
                12,
                count("select count(*) from listings")
        );

        assertEquals(
                2,
                count("""
                    select count(*)
                    from listings
                    where status='EXPIRED'
                    """)
        );

        assertEquals(
                3,
                jdbc.queryForObject("""
                    select count(*)
                    from requests
                    where listing_id = ?
                      and status = 'PENDING'
                    """,
                        Integer.class,
                        com.tesa.foodbridge.demo.DemoSeed.id("listing3")
                )
        );

        assertEquals(
                3,
                count("select count(*) from handovers")
        );
    }

    @Test void deletionSnapshotsPreviousStateAndPreservesHistory() throws Exception {
        UUID listing=listing(), pending=request(listing);
        String before=jdbc.queryForObject("select to_jsonb(l)::text from listings l where id=?",String.class,listing);
        String requestBefore=jdbc.queryForObject("select to_jsonb(r)::text from requests r where id=?",String.class,pending);
        listings.delete(donor,listing);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var details=json.readTree(jdbc.queryForObject("select details from activity where type='LISTING_ARCHIVED'",String.class));
        assertEquals("AVAILABLE",details.path("previous_status").asText());
        assertEquals(json.readTree(before),details.path("listing"));
        assertEquals(requestBefore,jdbc.queryForObject("select to_jsonb(r)::text from requests r where id=?",String.class,pending));
        assertEquals("DELETED",listings.get(listing).status());
        assertTrue(listings.browse(0,100).isEmpty());
        code("LISTING_NOT_FOUND",()->requests.request(recipient,listing,null));
        code("LISTING_NOT_FOUND",()->requests.approve(donor,pending));
        assertEquals(2,count("select count(*) from activity where type<>'LISTING_ARCHIVED'"));
    }
    @Test void concurrentAndRepeatedDeletionCreateOneEvent() throws Exception {
        UUID listing=listing();
        parallel(List.of(1,2,3),i->{listings.delete(donor,listing);return true;});
        listings.delete(donor,listing);
        assertEquals(1,count("select count(*) from activity where type='LISTING_ARCHIVED'"));
    }
    @Test void deletionPreservesApprovedRequestAndHandover() {
        UUID listing=listing(), request=request(listing);
        requests.approve(donor,request);
        String before=jdbc.queryForObject("select to_jsonb(h)::text from handovers h",String.class);
        code("LISTING_NOT_DELETABLE",()->listings.delete(user(Actor.Role.ADMIN),listing));
        assertEquals("APPROVED",requests.get(recipient,request).status());
        assertEquals(before,jdbc.queryForObject("select to_jsonb(h)::text from handovers h",String.class));
        assertEquals("RESERVED",listings.get(listing).status());
        assertEquals(0,count("select count(*) from activity where type='LISTING_ARCHIVED'"));
    }
    @Test void failedStatusUpdateRollsBackArchiveEvent() {
        UUID listing=listing();
        jdbc.execute("alter table listings add constraint test_no_delete check(status<>'DELETED')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->listings.delete(donor,listing));
            assertEquals("AVAILABLE",listings.get(listing).status());
            assertEquals(0,count("select count(*) from activity where type='LISTING_ARCHIVED'"));
        } finally { jdbc.execute("alter table listings drop constraint test_no_delete"); }
    }
    @Test void failedArchiveInsertLeavesListingUnchanged() {
        UUID listing=listing();
        jdbc.execute("alter table activity add constraint test_no_archive check(type<>'LISTING_ARCHIVED')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->listings.delete(donor,listing));
            assertEquals("AVAILABLE",listings.get(listing).status());
        } finally { jdbc.execute("alter table activity drop constraint test_no_archive"); }
    }
    @Test void deletionAuthorizationIncludesNoOpAndDatabaseRoleValidation() {
        UUID listing=listing();
        Actor stranger=user(Actor.Role.DONOR);
        code("FORBIDDEN",()->listings.delete(stranger,listing));
        code("FORBIDDEN",()->listings.delete(recipient,listing));
        code("FORBIDDEN",()->listings.delete(new Actor(recipient.id(),Actor.Role.ADMIN),listing));
        listings.delete(donor,listing);
        code("FORBIDDEN",()->listings.delete(stranger,listing));
        listings.delete(user(Actor.Role.ADMIN),listing);
        assertEquals(1,count("select count(*) from activity where type='LISTING_ARCHIVED'"));
    }
    @Test void httpDeletionRequiresAuthenticationAndOwnerOrAdmin() throws Exception {
        UUID ownerId=com.tesa.foodbridge.demo.DemoSeed.id("donor1");
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,'owner@example.invalid','disabled','Owner','DONOR')",ownerId);
        UUID listing=listings.create(new Actor(ownerId,Actor.Role.DONOR),command(BigDecimal.ONE)).id();
        String url="/api/listings/"+listing;
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .header("Authorization", bearer("recipient1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .header("Authorization", bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .header("Authorization", bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertEquals("DELETED", listings.get(listing).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from activity where listing_id=? and type='LISTING_ARCHIVED'", Integer.class, listing));
        assertEquals("AVAILABLE", jdbc.queryForObject("select details::jsonb->>'previous_status' from activity where listing_id=? and type='LISTING_ARCHIVED'", String.class, listing));
        UUID adminId=com.tesa.foodbridge.demo.DemoSeed.id("admin");
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,'admin@example.invalid','disabled','Admin','ADMIN')",adminId);
        UUID another=listing();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/listings/"+another)
                .header("Authorization", bearer("admin1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }
    @Test void deletedRowsAreExcludedBeforePublicAndOwnerPagination() {
        var visible=new HashSet<UUID>();
        for(int i=0;i<6;i++) {
            UUID id=listing();
            if(i%2==0) {
                listings.delete(donor,id);
                jdbc.update("update listings set created_at=clock_timestamp()+interval '1 day' where id=?",id);
            } else visible.add(id);
        }
        var seen=new HashSet<UUID>();
        for(int offset=0;offset<3;offset+=2) {
            var page=listings.browse(offset,2);
            assertEquals(Math.min(2,3-offset),page.size());
            assertEquals(page,listings.browseMine(donor,offset,2));
            page.forEach(row->{assertNotEquals("DELETED",row.status());assertTrue(seen.add(row.id()));});
        }
        assertEquals(visible,seen);
        assertTrue(listings.browse(3,2).isEmpty());
        assertTrue(listings.browseMine(donor,3,2).isEmpty());
    }
    @Test void deletedHistoryPreservesAuthorizedRequestsAndIdempotencyReplay() {
        UUID listing=listing();
        UUID pending=requests.request(recipient,listing,"history-replay").id();
        UUID declined=requests.request(user(Actor.Role.RECIPIENT),listing,null).id();
        listings.delete(donor,listing);
        code("LISTING_NOT_FOUND",()->listings.getPublic(listing));
        assertEquals("DELETED",listings.getHistorical(donor,listing).status());
        assertEquals("DELETED",listings.getHistorical(recipient,listing).status());
        assertEquals("DELETED",listings.getHistorical(user(Actor.Role.ADMIN),listing).status());
        code("FORBIDDEN",()->listings.getHistorical(user(Actor.Role.DONOR),listing));
        code("FORBIDDEN",()->listings.getHistorical(user(Actor.Role.RECIPIENT),listing));
        assertEquals(pending,requests.request(recipient,listing,"history-replay").id());
        assertEquals("CANCELLED",requests.cancel(recipient,pending).status());
        assertEquals("DECLINED",requests.decline(donor,declined).status());
    }
    @Test void historicalHttpDetailRequiresOwnerRequesterOrRealAdminIdentity() throws Exception {
        UUID ownerId=com.tesa.foodbridge.demo.DemoSeed.id("donor1");
        UUID recipientId=com.tesa.foodbridge.demo.DemoSeed.id("recipient1");
        UUID adminId=com.tesa.foodbridge.demo.DemoSeed.id("admin");
        for(var identity:List.of(Map.entry(ownerId,"DONOR"),Map.entry(recipientId,"RECIPIENT"),Map.entry(adminId,"ADMIN")))
            jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,'disabled','History test',?)",identity.getKey(),identity.getKey()+"@example.invalid",identity.getValue());
        UUID listing=listings.create(new Actor(ownerId,Actor.Role.DONOR),command(BigDecimal.ONE)).id();
        requests.request(new Actor(recipientId,Actor.Role.RECIPIENT),listing,null);
        listings.delete(new Actor(ownerId,Actor.Role.DONOR),listing);
        String url="/api/listings/"+listing;
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        for(String login:List.of("donor1:donor123","recipient1:recipient123","admin1:admin123"))
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                    .header("Authorization",bearer(login.split(":")[0])))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("DELETED"));
        UUID unrelated=listing();listings.delete(donor,unrelated);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings/"+unrelated)
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test void deletionAndApprovalSerializeWithoutLosingHistory() throws Exception {
        UUID listing=listing(), pending=request(listing);
        var results=parallel(List.of("DELETE","APPROVE"),op->{
            try { if(op.equals("DELETE")) listings.delete(donor,listing); else requests.approve(donor,pending); return op; }
            catch(RuleViolation e) { return e.code(); }
        });
        if(results.contains("DELETE")) {
            assertTrue(results.contains("LISTING_NOT_FOUND"));
            assertEquals("DELETED",listings.get(listing).status());
            assertEquals("PENDING",requests.get(recipient,pending).status());
            assertEquals(0,count("select count(*) from handovers"));
            assertEquals(1,count("select count(*) from activity where type='LISTING_ARCHIVED'"));
        } else {
            assertTrue(results.contains("APPROVE"));
            assertTrue(results.contains("LISTING_NOT_DELETABLE"));
            assertEquals("RESERVED",listings.get(listing).status());
            assertEquals(1,count("select count(*) from handovers"));
            assertEquals(0,count("select count(*) from activity where type='LISTING_ARCHIVED'"));
        }
    }
    @Test void protectedStatesReturnHttpConflict() throws Exception {
        UUID owner=com.tesa.foodbridge.demo.DemoSeed.id("donor1");
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,'owner@example.invalid','disabled','Owner','DONOR')",owner);
        for(String status:List.of("RESERVED","COLLECTED","REVERSED")) {
            UUID id=listings.create(new Actor(owner,Actor.Role.DONOR),command(BigDecimal.ONE)).id();
            jdbc.update("update listings set status=? where id=?",status,id);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/listings/"+id).header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            assertEquals(status,listings.get(id).status());
        }
        UUID expiredId=listings.create(new Actor(owner,Actor.Role.DONOR),command(BigDecimal.ONE)).id();
        jdbc.update("update listings set status='EXPIRED' where id=?",expiredId);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/listings/"+expiredId).header("Authorization",bearer("donor1")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertEquals("DELETED",listings.get(expiredId).status());
    }

    @Test void markAsCollectedAuthorizationAndStateTransitions() throws Exception {
        UUID donor1Id=com.tesa.foodbridge.demo.DemoSeed.id("donor1");
        UUID donor2Id=com.tesa.foodbridge.demo.DemoSeed.id("donor2");
        UUID recipient1Id=com.tesa.foodbridge.demo.DemoSeed.id("recipient1");
        UUID adminId=com.tesa.foodbridge.demo.DemoSeed.id("admin");
        for(var entry:List.of(Map.entry(donor1Id,"DONOR"),Map.entry(donor2Id,"DONOR"),Map.entry(recipient1Id,"RECIPIENT"),Map.entry(adminId,"ADMIN"))) {
            jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,'disabled','Test',?)",entry.getKey(),entry.getKey()+"@example.invalid",entry.getValue());
        }
        Actor donor1Actor=new Actor(donor1Id,Actor.Role.DONOR);
        Actor recipient1Actor=new Actor(recipient1Id,Actor.Role.RECIPIENT);

        // 1. Owning donor marks approved request as collected -> 200 OK, and auto-declines other pending requests on approval
        UUID listing1=listings.create(donor1Actor,command(new BigDecimal("15"))).id();
        UUID req1=requests.request(recipient1Actor,listing1,null).id();
        UUID otherPendingReq=requests.request(recipient,listing1,null).id();
        requests.approve(donor1Actor,req1);

        // Auto-declined other pending request in the same transaction, and FoodRequestResponse exposes handoverId & handoverStatus
        assertEquals("APPROVED",requests.get(donor1Actor,req1).status());
        assertNotNull(requests.get(donor1Actor,req1).handoverId());
        assertEquals("AWAITING_COLLECTION",requests.get(donor1Actor,req1).handoverStatus());
        assertEquals("DECLINED",requests.get(donor1Actor,otherPendingReq).status());
        assertNotNull(requests.get(donor1Actor,otherPendingReq).decidedAt());

        // Verify GET /api/requests/{requestId} returns handoverId and handoverStatus in JSON
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/requests/"+req1)
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.handoverId").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.handoverStatus").value("AWAITING_COLLECTION"));

        // Verify durable token is persisted in auth_tokens table and authenticates /api/auth/me
        String durableBearer=bearer("donor1");
        assertTrue(count("select count(*) from auth_tokens where username='donor1' and revoked=false")>=1);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me")
                .header("Authorization",durableBearer))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.role").value("DONOR"));

        // Unrelated donor rejected with 403 FORBIDDEN
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req1+"/collected")
                .header("Authorization",bearer("donor2")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));

        // Recipient rejected with 403 FORBIDDEN
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req1+"/collected")
                .header("Authorization",bearer("recipient1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));

        // Owning donor succeeds with 200 OK and receives handoverId in response
        var collectRes=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req1+"/collected")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.handoverId").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("COLLECTED"))
                .andReturn();
        String returnedHandoverId=new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(collectRes.getResponse().getContentAsString()).get("handoverId").asText();
        assertEquals("COLLECTED",listings.get(listing1).status());

        // Repeat collection on already collected request returns 409 CONFLICT (not 403)
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req1+"/collected")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("HANDOVER_NOT_PENDING"));

        // Reversal authorisation: unrelated donor & recipient rejected with 403 FORBIDDEN
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/handovers/"+returnedHandoverId+"/reverse")
                .header("Authorization",bearer("donor2")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/handovers/"+returnedHandoverId+"/reverse")
                .header("Authorization",bearer("recipient1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));

        // Owning donor reverses handover using handoverId from /collected response -> listing becomes AVAILABLE, request becomes REVERSED
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/handovers/"+returnedHandoverId+"/reverse")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.handoverId").value(returnedHandoverId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("REVERSED"));
        assertEquals("AVAILABLE",listings.get(listing1).status());
        assertEquals("REVERSED",requests.get(donor1Actor,req1).status());

        // 2. ADMIN marks approved request as collected -> 200 OK (using `recipient` from @BeforeEach so daily fairness first-round is satisfied)
        UUID listing2=listings.create(donor1Actor,command(new BigDecimal("10"))).id();
        UUID req2=requests.request(recipient,listing2,null).id();
        requests.approve(donor1Actor,req2);

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req2+"/collected")
                .header("Authorization",bearer("admin1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.handoverId").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("COLLECTED"));
        assertEquals("COLLECTED",listings.get(listing2).status());

        // 3. Reversing an APPROVED (not yet collected) request using requestId:
        //    Only owning donor may reverse; request -> REVERSED, listing -> AVAILABLE, and listing can be requested/approved again
        UUID listing3=listings.create(donor1Actor,command(new BigDecimal("12"))).id();
        UUID req3=requests.request(recipient1Actor,listing3,null).id();
        requests.approve(donor1Actor,req3);
        assertEquals("RESERVED",listings.get(listing3).status());

        // Unrelated donor and recipient rejected with 403 FORBIDDEN when trying to reverse by requestId
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req3+"/reverse")
                .header("Authorization",bearer("donor2")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req3+"/reverse")
                .header("Authorization",bearer("recipient1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));

        // Owning donor reverses APPROVED (not yet collected) request using requestId -> 200 OK
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+req3+"/reverse")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.handoverId").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("REVERSED"));
        assertEquals("REVERSED",requests.get(donor1Actor,req3).status());
        assertEquals("AVAILABLE",listings.get(listing3).status());

        // Reopened AVAILABLE listing can now accept and approve another eligible request
        Actor freshRecipient=user(Actor.Role.RECIPIENT);
        UUID req4=requests.request(freshRecipient,listing3,null).id();
        requests.approve(donor1Actor,req4);
        assertEquals("APPROVED",requests.get(donor1Actor,req4).status());
        assertEquals("RESERVED",listings.get(listing3).status());
    }

    @Test void listingDatabaseFilteringPaginationCountAndAvailabilitySemantics() throws Exception {
        UUID donor1Id=com.tesa.foodbridge.demo.DemoSeed.id("donor1");
        UUID adminId=com.tesa.foodbridge.demo.DemoSeed.id("admin");
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,'d1@example.invalid','disabled','Donor 1','DONOR')",donor1Id);
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,'admin@example.invalid','disabled','Admin','ADMIN')",adminId);
        Actor donor1Actor=new Actor(donor1Id,Actor.Role.DONOR);

        // Create 15 AVAILABLE BAKERY listings in Yaba
        for(int i=1;i<=15;i++) {
            listings.create(donor1Actor,new ListingOperations.CreateListing(
                    "Bread Batch "+i,"Fresh bakery loaves","BAKERY",new BigDecimal("10"),"PORTIONS",null,"Yaba",Instant.now().plusSeconds(7200)
            ));
        }
        // Create 2 AVAILABLE PRODUCE listings in Ikeja, then expire 1 by deadline and 1 by status
        UUID overdueAvailable=listings.create(donor1Actor,new ListingOperations.CreateListing(
                "Overdue Apples","Fresh produce","PRODUCE",new BigDecimal("5"),"KG",null,"Ikeja",Instant.now().plusSeconds(3600)
        )).id();
        jdbc.update("update listings set collection_deadline=clock_timestamp()-interval '1 hour' where id=?",overdueAvailable);

        UUID explicitExpired=listings.create(donor1Actor,new ListingOperations.CreateListing(
                "Expired Oranges","Citrus fruit","PRODUCE",new BigDecimal("8"),"KG",null,"Ikeja",Instant.now().plusSeconds(3600)
        )).id();
        jdbc.update("update listings set status='EXPIRED', collection_deadline=clock_timestamp()-interval '2 hours' where id=?",explicitExpired);

        // Create 1 RESERVED and 1 COLLECTED listing
        UUID reservedListing=listings.create(donor1Actor,new ListingOperations.CreateListing(
                "Reserved Rice","Jollof tray","PREPARED_MEALS",new BigDecimal("25"),"PORTIONS",null,"Yaba",Instant.now().plusSeconds(7200)
        )).id();
        jdbc.update("update listings set status='RESERVED' where id=?",reservedListing);

        UUID collectedListing=listings.create(donor1Actor,new ListingOperations.CreateListing(
                "Collected Beans","Cooked beans","PREPARED_MEALS",new BigDecimal("30"),"PORTIONS",null,"Yaba",Instant.now().plusSeconds(7200)
        )).id();
        jdbc.update("update listings set status='COLLECTED' where id=?",collectedListing);

        // Page 1: availability=AVAILABLE, limit=12, offset=0 -> returns 12 items, X-Total-Count=15 (overdueAvailable excluded!)
        var page1=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("offset","0").param("limit","12").param("availability","AVAILABLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","15"))
                .andReturn();
        var page1Json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(page1.getResponse().getContentAsString());
        assertEquals(12,page1Json.size());

        // Page 2: availability=AVAILABLE, limit=12, offset=12 -> returns 3 items, X-Total-Count=15
        var page2=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("offset","12").param("limit","12").param("availability","AVAILABLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","15"))
                .andReturn();
        var page2Json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(page2.getResponse().getContentAsString());
        assertEquals(3,page2Json.size());

        // Empty page beyond total count: offset=24, limit=12 -> 0 items, X-Total-Count=15
        var emptyPage=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("offset","24").param("limit","12").param("availability","AVAILABLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","15"))
                .andReturn();
        assertEquals(0,new com.fasterxml.jackson.databind.ObjectMapper().readTree(emptyPage.getResponse().getContentAsString()).size());

        // EXPIRED filter returns both explicit EXPIRED and overdue AVAILABLE (total 2)
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("availability","EXPIRED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(2));

        // RESERVED and COLLECTED filters
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("availability","RESERVED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(reservedListing.toString()));

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("availability","COLLECTED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(collectedListing.toString()));

        // Combined filters: category=PREPARED_MEALS & pickupArea=Yaba & keyword=Beans -> 1 match
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("category","PREPARED_MEALS").param("pickupArea","Yaba").param("keyword","Beans"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(collectedListing.toString()));

        // Soft-delete an expired listing: donor1 deletes explicitExpired -> excluded from public EXPIRED, visible to ADMIN with deleted=true & deletedAt
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/listings/"+explicitExpired)
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());

        // Public EXPIRED now has 1 (only overdueAvailable)
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("availability","EXPIRED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","1"));

        // Admin EXPIRED sees both (2), including soft-deleted record with deleted=true and deletedAt populated
        var adminExpired=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")
                .param("availability","EXPIRED").param("limit","50")
                .header("Authorization",bearer("admin1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","2"))
                .andReturn();
        var adminJson=new com.fasterxml.jackson.databind.ObjectMapper().readTree(adminExpired.getResponse().getContentAsString());
        boolean foundDeleted=false;
        for(var node:adminJson) {
            if(node.get("id").asText().equals(explicitExpired.toString())) {
                foundDeleted=true;
                assertEquals("DELETED",node.get("status").asText());
                assertTrue(node.get("deleted").asBoolean());
                assertFalse(node.get("deletedAt").isNull());
            }
        }
        assertTrue(foundDeleted);
    }

    @Test void adminDashboardAndAdminRequestsEndpoints() throws Exception {
        UUID donor1Id=com.tesa.foodbridge.demo.DemoSeed.id("donor1");
        UUID recipient1Id=com.tesa.foodbridge.demo.DemoSeed.id("recipient1");
        UUID recipient2Id=com.tesa.foodbridge.demo.DemoSeed.id("recipient2");
        UUID adminId=com.tesa.foodbridge.demo.DemoSeed.id("admin");
        for(var entry:List.of(Map.entry(donor1Id,"DONOR"),Map.entry(recipient1Id,"RECIPIENT"),Map.entry(recipient2Id,"RECIPIENT"),Map.entry(adminId,"ADMIN"))) {
            jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,'disabled','Test',?)",entry.getKey(),entry.getKey()+"@example.invalid",entry.getValue());
        }
        Actor donor1Actor=new Actor(donor1Id,Actor.Role.DONOR);
        Actor recipient1Actor=new Actor(recipient1Id,Actor.Role.RECIPIENT);
        Actor recipient2Actor=new Actor(recipient2Id,Actor.Role.RECIPIENT);

        // Create PORTIONS listing (20 portions) -> request -> approve -> collect
        UUID portionsListing=listings.create(donor1Actor,new ListingOperations.CreateListing(
                "20 Portions Rice","Lunch portions","PREPARED_MEALS",new BigDecimal("20"),"PORTIONS",null,"Lagos",Instant.now().plusSeconds(7200)
        )).id();
        UUID reqPortions=requests.request(recipient1Actor,portionsListing,null).id();
        requests.approve(donor1Actor,reqPortions);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+reqPortions+"/collected")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        // Create KG listing (50 KG) -> request -> approve -> collect (must NOT be added to portions estimate!)
        UUID kgListing=listings.create(donor1Actor,new ListingOperations.CreateListing(
                "50 KG Flour","Bulk flour","PANTRY",new BigDecimal("50"),"KG",null,"Lagos",Instant.now().plusSeconds(7200)
        )).id();
        UUID reqKg=requests.request(recipient2Actor,kgListing,null).id();
        requests.approve(donor1Actor,reqKg);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+reqKg+"/collected")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        // Create 1 expired listing and 1 available listing with a pending request
        UUID expiredListing=listings.create(donor1Actor,command(BigDecimal.TEN)).id();
        jdbc.update("update listings set status='EXPIRED' where id=?",expiredListing);
        UUID openListing=listings.create(donor1Actor,command(BigDecimal.TEN)).id();
        UUID pendingReq=requests.request(recipient1Actor,openListing,null).id();

        // Verify dashboard figures
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/dashboard")
                .header("Authorization",bearer("admin1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.availableListings").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.collectedListings").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.expiredListings").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.pendingRequests").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.completedHandovers").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.estimatedPortionsSaved").value(20));

        // Reverse the portions handover -> listing returns to AVAILABLE, request becomes REVERSED, estimatedPortionsSaved drops back to 0
        UUID handoverId=jdbc.queryForObject("select id from handovers where request_id=?",UUID.class,reqPortions);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/handovers/"+handoverId+"/reverse")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.handoverId").value(handoverId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("REVERSED"));
        assertEquals("AVAILABLE",listings.get(portionsListing).status());
        assertEquals("REVERSED",requests.get(donor1Actor,reqPortions).status());

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/dashboard")
                .header("Authorization",bearer("admin1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.availableListings").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.collectedListings").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.completedHandovers").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.estimatedPortionsSaved").value(0));

        // Verify GET /api/admin/requests authorization, status filtering, pagination and X-Total-Count
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/requests")
                .header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/requests")
                .header("Authorization",bearer("recipient1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/requests")
                .param("status","PENDING").param("offset","0").param("limit","10")
                .header("Authorization",bearer("admin1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(pendingReq.toString()));

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/requests")
                .param("offset","0").param("limit","1")
                .header("Authorization",bearer("admin1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count","3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1));
    }

    // --------------------------------------------------
    // PARALLEL EXECUTION HELPER
    // --------------------------------------------------

    static <T, R> List<R> parallel(
            List<T> values,
            java.util.function.Function<T, R> operation)
            throws Exception {

        var ready = new CountDownLatch(values.size());
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(values.size())) {

            List<Future<R>> futures = new ArrayList<>();

            for (T value : values) {

                futures.add(
                        pool.submit(() -> {

                            ready.countDown();

                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("start timeout");
                            }

                            return operation.apply(value);
                        })
                );
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS)
            );

            start.countDown();

            List<R> results = new ArrayList<>();

            for (var future : futures) {

                results.add(
                        future.get(30, TimeUnit.SECONDS)
                );
            }

            return results;

        } finally {

            start.countDown();
        }
    }
}
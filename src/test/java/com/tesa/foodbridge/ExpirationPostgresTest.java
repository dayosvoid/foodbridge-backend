package com.tesa.foodbridge;

import com.tesa.foodbridge.expiration.api.ExpirationOperations;
import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.reservation.api.ReservationOperations;
import com.tesa.foodbridge.handover.api.HandoverOperations;
import com.tesa.foodbridge.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"foodbridge.demo.seed-enabled=false","foodbridge.expiration.initial-delay-ms=86400000"})
@Testcontainers
@ActiveProfiles("demo")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class ExpirationPostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl); r.add("spring.datasource.username",DB::getUsername); r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ListingOperations listings;
    @Autowired ReservationOperations requests;
    @Autowired HandoverOperations handovers;
    @Autowired ExpirationOperations expiration;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.tesa.foodbridge.expiration.service.DatabaseExpirationOperations expirationSpy;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    Actor donor,recipient;
    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(expirationSpy,"after",null);
        jdbc.execute("truncate activity,handovers,requests,listings,users");
        donor=user("donor1",Actor.Role.DONOR); recipient=user("recipient1",Actor.Role.RECIPIENT);
    }
    Actor user(String name,Actor.Role role) {
        UUID id=com.tesa.foodbridge.demo.DemoSeed.id(name);
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,'disabled',?,?)",id,name+"@demo.foodbridge.invalid",name,role.name());
        return new Actor(id,role);
    }
    ListingOperations.CreateListing command() {return new ListingOperations.CreateListing("Food","Test","OTHER",BigDecimal.ONE,"ITEMS",null,"Lagos",Instant.now().plusSeconds(86400));}
    UUID listing() {return listings.create(donor,command()).id();}
    UUID request(UUID id) {return requests.request(recipient,id,UUID.randomUUID().toString()).id();}
    void due(UUID id) {jdbc.update("update listings set collection_deadline='2000-01-01T00:00:00Z' where id=?",id);}
    void expire(UUID id) {new TransactionTemplate(manager).executeWithoutResult(s->expiration.expireLocked(id));}
    int count(String sql) {return jdbc.queryForObject(sql,Integer.class);}
    String snapshot() {return jdbc.queryForObject("select jsonb_build_object('listings',(select jsonb_agg(to_jsonb(l) order by id) from listings l),'requests',(select jsonb_agg(to_jsonb(r) order by id) from requests r),'handovers',(select jsonb_agg(to_jsonb(h) order by id) from handovers h),'activity',(select jsonb_agg(to_jsonb(a) order by id) from activity a))::text",String.class);}
    @Test void availableWithoutRequests() {
        UUID id=listing();due(id);
        Instant before=jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();
        expire(id);
        Instant after=jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();
        var row=listings.get(id);assertEquals("EXPIRED",row.status());
        assertFalse(row.expiredAt().isBefore(before));assertFalse(row.expiredAt().isAfter(after));
        assertNotEquals(row.collectionDeadline(),row.expiredAt());
        assertEquals(row.expiredAt(),jdbc.queryForObject("select created_at from activity where type='LISTING_EXPIRED'",Timestamp.class).toInstant());
    }





    @ParameterizedTest @ValueSource(strings={"reverse-reverse","reverse-expire"})
    void lifecycleRacesHaveOneConsistentOutcome(String scenario) throws Exception {
        UUID id=listing(),r=request(id);requests.approve(donor,r);
        UUID h=jdbc.queryForObject("select id from handovers where request_id=?",UUID.class,r);
        handovers.completeByDonorOrAdmin(r,donor.id(),false);due(id);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.function.Function<Boolean,java.util.concurrent.Callable<Boolean>> action=second->()->{
                ready.countDown();assertTrue(start.await(20,TimeUnit.SECONDS));
                try {
                    if(second&&scenario.endsWith("expire"))expire(id);
                    else handovers.reverseByDonorOrAdmin(h,donor.id(),false);
                    return true;
                } catch(RuleViolation expected) {return false;}
            };
            var first=pool.submit(action.apply(false));var second=pool.submit(action.apply(true));
            assertTrue(ready.await(20,TimeUnit.SECONDS));start.countDown();
            int successes=(first.get(30,TimeUnit.SECONDS)?1:0)+(second.get(30,TimeUnit.SECONDS)?1:0);
            assertEquals(scenario.equals("reverse-expire")?2:1,successes);
        } finally {start.countDown();}
        String state=listings.get(id).status();
        assertTrue(java.util.Set.of("AVAILABLE","EXPIRED").contains(state));
        assertEquals("REVERSED",requests.get(donor,r).status());
        assertEquals(1,count("select count(*) from activity where type='REVERSED'"));
    }



    @Test void reversalActivityFailureRollsBack() {
        UUID id=listing(),r=request(id);requests.approve(donor,r);
        UUID h=jdbc.queryForObject("select id from handovers where request_id=?",UUID.class,r);
        handovers.completeByDonorOrAdmin(r,donor.id(),false);
        String before=snapshot();
        jdbc.execute("alter table activity add constraint lifecycle_failure check(type<>'REVERSED')");
        try {
            assertThrows(RuntimeException.class,()->handovers.reverseByDonorOrAdmin(h,donor.id(),false));
            assertEquals(before,snapshot());
        } finally {jdbc.execute("alter table activity drop constraint lifecycle_failure");}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void reversalAndExpirationBothLockOrders(boolean expirationFirst) throws Exception {
        UUID id=listing(),r=request(id);requests.approve(donor,r);
        UUID h=handovers.completeByDonorOrAdmin(r,donor.id(),false);due(id);
        var firstDone=new CountDownLatch(1);var release=new CountDownLatch(1);
        Runnable reverse=()->handovers.reverseByDonorOrAdmin(h,donor.id(),false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{
                if(expirationFirst)expiration.expireLocked(id);else reverse.run();
                firstDone.countDown();
                try {assertTrue(release.await(20,TimeUnit.SECONDS));}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertTrue(firstDone.await(20,TimeUnit.SECONDS));
            var second=pool.submit(()->{if(expirationFirst)reverse.run();else expire(id);});
            release.countDown();first.get(30,TimeUnit.SECONDS);second.get(30,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertEquals(expirationFirst?"AVAILABLE":"EXPIRED",listings.get(id).status());
        assertEquals("REVERSED",requests.get(donor,r).status());
        assertEquals("REVERSED",jdbc.queryForObject("select status from handovers where id=?",String.class,h));
        // If expiration saw COLLECTED first, the next pass expires the reopened listing.
        expire(id);assertEquals("EXPIRED",listings.get(id).status());
    }

    @Test void reopenedListingCanBeRequestedAndReservedBeforeOriginalDeadline() {
        UUID id=listing(),r=request(id);requests.approve(donor,r);
        UUID h=handovers.completeByDonorOrAdmin(r,donor.id(),false);
        Instant deadline=listings.get(id).collectionDeadline();
        handovers.reverseByDonorOrAdmin(h,donor.id(),false);
        String history=jdbc.queryForObject("select to_jsonb(h)::text from handovers h where id=?",String.class,h);
        UUID next=request(id);requests.approve(donor,next);
        assertEquals("RESERVED",listings.get(id).status());
        assertEquals(deadline,listings.get(id).collectionDeadline());
        due(id);expire(id);
        assertEquals("EXPIRED",requests.get(donor,next).status());
        assertEquals("REVERSED",requests.get(donor,r).status());
        assertEquals(history,jdbc.queryForObject("select to_jsonb(h)::text from handovers h where id=?",String.class,h));
    }

    @Test void reopenedOverdueListingExpiresOnNextRequestWithoutChangingReversalHistory() {
        UUID id=listing(),r=request(id);requests.approve(donor,r);
        UUID h=handovers.completeByDonorOrAdmin(r,donor.id(),false);due(id);
        handovers.reverseByDonorOrAdmin(h,donor.id(),false);
        assertEquals("AVAILABLE",listings.get(id).status());
        String history=jdbc.queryForObject("select to_jsonb(h)::text from handovers h where id=?",String.class,h);
        assertEquals("LISTING_EXPIRED",assertThrows(RuleViolation.class,()->request(id)).code());
        assertEquals("EXPIRED",listings.get(id).status());
        assertEquals("REVERSED",requests.get(donor,r).status());
        assertEquals(history,jdbc.queryForObject("select to_jsonb(h)::text from handovers h where id=?",String.class,h));
    }

    @Test void legacyApprovedRequestWithReversedHandoverRemainsHistorical() {
        UUID id=listing(),r=request(id);requests.approve(donor,r);
        UUID h=handovers.completeByDonorOrAdmin(r,donor.id(),false);
        handovers.reverseByDonorOrAdmin(h,donor.id(),false);
        jdbc.update("update requests set status='APPROVED' where id=?",r);
        String requestBefore=jdbc.queryForObject("select to_jsonb(r)::text from requests r where id=?",String.class,r);
        String handoverBefore=jdbc.queryForObject("select to_jsonb(h)::text from handovers h where id=?",String.class,h);
        due(id);expire(id);
        assertEquals("EXPIRED",listings.get(id).status());
        assertEquals(requestBefore,jdbc.queryForObject("select to_jsonb(r)::text from requests r where id=?",String.class,r));
        assertEquals(handoverBefore,jdbc.queryForObject("select to_jsonb(h)::text from handovers h where id=?",String.class,h));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void reversalPreservesHistoryAndAdjustsDashboardExactlyOnce(boolean admin) throws Exception {
        UUID id=listings.create(donor,new ListingOperations.CreateListing("Food","Test","OTHER",new BigDecimal("7"),"PORTIONS",null,"Lagos",Instant.now().plusSeconds(86400))).id();
        UUID r=request(id);requests.approve(donor,r);
        Instant approval=requests.get(donor,r).decidedAt();
        UUID h=handovers.completeByDonorOrAdmin(r,donor.id(),false);
        Timestamp completed=jdbc.queryForObject("select completed_at from handovers where id=?",Timestamp.class,h);
        Actor administrator=user("admin",Actor.Role.ADMIN);
        assertEquals(7,dashboard().estimatedPortionsSaved());
        due(id);Actor actor=admin?administrator:donor;
        handovers.reverseByDonorOrAdmin(h,actor.id(),admin);
        assertEquals("AVAILABLE",listings.get(id).status());
        assertEquals("REVERSED",requests.get(donor,r).status());
        assertFalse(requests.get(donor,r).decidedAt().isBefore(approval));
        assertEquals(completed,jdbc.queryForObject("select completed_at from handovers where id=?",Timestamp.class,h));
        assertEquals(0,dashboard().estimatedPortionsSaved());
        assertEquals(0,dashboard().availableListings());
        String before=snapshot();assertThrows(RuleViolation.class,()->handovers.reverseByDonorOrAdmin(h,actor.id(),admin));
        assertEquals(before,snapshot());expire(id);assertEquals("EXPIRED",listings.get(id).status());
    }

    com.tesa.foodbridge.dashboard.api.AdminDashboardResponse dashboard() throws Exception {
        var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/dashboard")
                .header("Authorization",bearer("admin")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.getResponse().getContentAsString(),
                com.tesa.foodbridge.dashboard.api.AdminDashboardResponse.class);
    }
    @Test void multiplePendingAndRepeatAreAtomicAndIdempotent() {
        UUID id=listing();request(id);request(id);due(id);expire(id);
        assertEquals(2,count("select count(*) from requests r join listings l on l.id=r.listing_id where r.status='EXPIRED' and r.decided_at=r.expired_at and r.expired_at=l.expired_at and r.expired_at>l.collection_deadline"));
        String before=snapshot();
        org.mockito.Mockito.doReturn(Timestamp.from(Instant.parse("2100-01-01T00:00:00Z"))).when(expirationSpy).decisionTime();
        expire(id);expiration.expireDue();assertEquals(before,snapshot());
        assertEquals(1,count("select count(*) from activity where type='LISTING_EXPIRED' and actor_id is null"));
    }
    @Test void reservedPreservesApprovalTimeAndExpiresAwaitingAndCompetitors() {
        UUID id=listing(),approved=request(id);request(id);requests.approve(donor,approved);
        // A legacy pending competitor can predate main's auto-decline rule.
        jdbc.update("insert into requests(id,listing_id,requester_id) values (?,?,?)",UUID.randomUUID(),id,recipient.id());
        Timestamp decision=jdbc.queryForObject("select decided_at from requests where id=?",Timestamp.class,approved);
        due(id);expire(id);
        assertEquals(2,count("select count(*) from requests where status='EXPIRED'"));
        assertEquals(1,count("select count(*) from requests where status='DECLINED'"));
        assertEquals(1,count("select count(*) from handovers where status='EXPIRED' and completed_at is null and reversed_at is null"));
        assertEquals(decision,jdbc.queryForObject("select decided_at from requests where id=?",Timestamp.class,approved));
        assertEquals(1,count("select count(*) from handovers h join requests r on r.id=h.request_id join listings l on l.id=r.listing_id join activity a on a.listing_id=l.id and a.type='LISTING_EXPIRED' where h.expired_at=r.expired_at and r.expired_at=l.expired_at and l.expired_at=a.created_at and l.updated_at=l.expired_at and l.expired_at>l.collection_deadline"));
        String before=snapshot();
        org.mockito.Mockito.doReturn(Timestamp.from(Instant.parse("2100-01-01T00:00:00Z"))).when(expirationSpy).decisionTime();
        expire(id);assertEquals(before,snapshot());
    }
    @Test void commandsCommitExpirationBeforeConflict() {
        UUID id=listing(),r=request(id);due(id);
        assertEquals("LISTING_EXPIRED",assertThrows(RuleViolation.class,()->requests.approve(donor,r)).code());
        assertEquals("EXPIRED",listings.get(id).status());
        assertEquals("LISTING_EXPIRED",assertThrows(RuleViolation.class,()->request(id)).code());
    }
    @Test void lateCollectionCommitsExpiration() {
        UUID id=listing(),r=request(id);requests.approve(donor,r);due(id);
        assertEquals("LISTING_EXPIRED",assertThrows(RuleViolation.class,()->handovers.completeByDonorOrAdmin(r,donor.id(),false)).code());
        assertEquals("EXPIRED",listings.get(id).status());
    }
    @Test void completedAndReversedHistorySurvivesDeadline() {
        for(boolean reverse:List.of(false,true)) {
            UUID id=listing(),r=request(id);requests.approve(donor,r);handovers.completeByDonorOrAdmin(r,donor.id(),false);
            if(reverse) handovers.reverseByDonorOrAdmin(jdbc.queryForObject("select id from handovers where request_id=?",UUID.class,r),donor.id(),false);
            due(id);String before=snapshot();expire(id);if(!reverse)assertEquals(before,snapshot());
            assertEquals(reverse?"EXPIRED":"COLLECTED",listings.getPublic(id).status());
        }
    }
    @Test void legacyRepairDoesNotInventListingTimestamp() {
        UUID id=listing(),r=request(id);requests.approve(donor,r);due(id);
        jdbc.update("update listings set status='EXPIRED' where id=?",id);expire(id);
        assertNull(listings.get(id).expiredAt());
        assertEquals("LEGACY_EXPIRATION_RECONCILIATION",requests.get(recipient,r).expirationReason());
        assertEquals(1,count("select count(*) from handovers where status='EXPIRED'"));
        assertEquals(1,count("select count(*) from handovers h join requests r on r.id=h.request_id join listings l on l.id=r.listing_id join activity a on a.listing_id=l.id and a.type='LISTING_EXPIRED' where l.expired_at is null and h.expired_at=r.expired_at and r.expired_at=a.created_at and r.expired_at>l.collection_deadline"));
        String before=snapshot();expire(id);assertEquals(before,snapshot());
    }
    @ParameterizedTest @ValueSource(strings={"requests","handovers","activity"})
    void failureRollsBackEverything(String table) {
        UUID id=listing(),r=request(id);if(table.equals("handovers"))requests.approve(donor,r);due(id);String before=snapshot();
        jdbc.execute("alter table "+table+" add constraint expiration_failure_test check ("+(table.equals("activity")?"type<>'LISTING_EXPIRED'":"status<>'EXPIRED'")+")");
        try {assertThrows(RuntimeException.class,()->expire(id));assertEquals(before,snapshot());}
        finally {jdbc.execute("alter table "+table+" drop constraint expiration_failure_test");}
    }
    @Test void unauthorizedCommandsAndReplayCannotMutate() {
        UUID id=listing();String key="owned";UUID r=requests.request(recipient,id,key).id();due(id);String before=snapshot();
        Actor stranger=user("donor2",Actor.Role.DONOR);
        assertThrows(RuleViolation.class,()->requests.approve(stranger,r));assertEquals(before,snapshot());
        expire(id);String expired=snapshot();assertEquals(r,requests.request(recipient,id,key).id());assertEquals(expired,snapshot());
        Actor other=user("recipient2",Actor.Role.RECIPIENT);
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(RuleViolation.class,()->requests.request(other,id,key)).code());
    }
    @ParameterizedTest @ValueSource(strings={"edit","delete","cancel","decline"})
    void overdueMutationsCannotReopenOrMislabel(String action) {
        UUID id=listing(),r=request(id);due(id);
        assertEquals("LISTING_EXPIRED",assertThrows(RuleViolation.class,()->{
            switch(action) {case "edit"->listings.update(donor,id,command());case "delete"->listings.delete(donor,id);case "cancel"->requests.cancel(recipient,r);default->requests.decline(donor,r);}
        }).code());assertEquals("EXPIRED",listings.get(id).status());assertEquals("EXPIRED",requests.get(recipient,r).status());
    }
    @Test void concurrentWorkersProduceOneEvent() throws Exception {
        UUID id=listing();request(id);due(id);
        try(var pool=Executors.newFixedThreadPool(2)) {var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();expiration.expireDue();return true;});var b=pool.submit(()->{start.await();expiration.expireDue();return true;});start.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);}
        assertEquals(1,count("select count(*) from activity where type='LISTING_EXPIRED'"));
    }
    @Test void schedulerSkipsLockedAggregateAndRetriesOnNextCycle() throws Exception {
        UUID id=listing();request(id);due(id);
        try(var c=DB.createConnection("")) {
            c.setAutoCommit(false);
            try(var statement=c.prepareStatement("select id from listings where id=? for update")) {statement.setObject(1,id);statement.executeQuery().close();}
            try(var pool=Executors.newSingleThreadExecutor()) {pool.submit(()->expiration.expireDue()).get(10,TimeUnit.SECONDS);}
            assertEquals("AVAILABLE",listings.get(id).status());c.commit();
        }
        expiration.expireDue();expiration.expireDue();assertEquals("EXPIRED",listings.get(id).status());
    }
    String bearer(String name) throws Exception {
        String password=name.equals("admin")?"admin123":"donor123";
        var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                .contentType("application/json").content("{\"email\":\""+name+"@demo.foodbridge.invalid\",\"password\":\""+password+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        return "Bearer "+new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).path("token").asText();
    }
    @Test void httpConflictMetadataAndSystemActivity() throws Exception {
        user("admin",Actor.Role.ADMIN);
        UUID id=listing(),r=request(id);due(id);
        org.mockito.Mockito.doReturn(Timestamp.from(Instant.parse("2030-01-01T00:00:00Z"))).when(expirationSpy).decisionTime();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+r+"/approve").header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("LISTING_EXPIRED"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings/"+id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.expiredAt").value("2030-01-01T00:00:00Z"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.expirationReason").value("COLLECTION_DEADLINE_PASSED"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/activity").header("Authorization",bearer("admin")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].type").value("LISTING_EXPIRED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].actorName").isEmpty());
    }
    @Test void overdueHistoryDoesNotCreatePaginationHolesOrWriteOnRead() throws Exception {
        for(int i=0;i<4;i++) {UUID id=listing(),r=request(id);requests.approve(donor,r);handovers.completeByDonorOrAdmin(r,donor.id(),false);due(id);}
        String before=snapshot();
        for(int offset:List.of(0,2)) {
            var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings?limit=2&offset="+offset))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
            assertEquals(2,new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).size());
        }
        assertEquals(before,snapshot());
    }
    @Test void contradictoryLegacyCompletedHistoryIsPreserved() {
        UUID id=listing(),r=request(id);requests.approve(donor,r);handovers.completeByDonorOrAdmin(r,donor.id(),false);due(id);
        jdbc.update("update listings set status='EXPIRED' where id=?",id);
        String before=snapshot();expire(id);assertEquals(before,snapshot());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void exactDeadlineBoundary(long offsetMicros) {
        UUID id=listing();
        Timestamp deadline=Timestamp.from(Instant.parse("2099-01-01T00:00:00Z"));
        jdbc.update("update listings set collection_deadline=? where id=?",deadline,id);
        org.mockito.Mockito.doReturn(Timestamp.from(deadline.toInstant().plusNanos(offsetMicros*1000)))
                .when(expirationSpy).decisionTime();
        expire(id);
        assertEquals(offsetMicros<0?"AVAILABLE":"EXPIRED",listings.get(id).status());
    }
    @ParameterizedTest @ValueSource(strings={"request","approve","collect","edit","delete","cancel","decline"})
    void equalityRejectsCommandsAndCommitsClosure(String action) {
        UUID id=listing(),r=request(id);if(action.equals("collect"))requests.approve(donor,r);
        Timestamp deadline=Timestamp.from(Instant.parse("2099-01-01T00:00:00Z"));
        jdbc.update("update listings set collection_deadline=? where id=?",deadline,id);
        org.mockito.Mockito.doReturn(deadline).when(expirationSpy).decisionTime();
        assertEquals("LISTING_EXPIRED",assertThrows(RuleViolation.class,()->{
            switch(action) {
                case "request" -> request(id);
                case "approve" -> requests.approve(donor,r);
                case "collect" -> handovers.completeByDonorOrAdmin(r,donor.id(),false);
                case "edit" -> listings.update(donor,id,command());
                case "delete" -> listings.delete(donor,id);
                case "cancel" -> requests.cancel(recipient,r);
                default -> requests.decline(donor,r);
            }
        }).code());
        assertEquals("EXPIRED",listings.get(id).status());
        assertEquals(1,count("select count(*) from activity where type='LISTING_EXPIRED'"));
    }
    @ParameterizedTest @ValueSource(strings={"approval-first","expiration-before-approval","collection-first","expiration-before-collection"})
    void racesSerializeAtListingLock(String scenario) throws Exception {
        UUID id=listing(),r=request(id);
        boolean collection=scenario.contains("collection"), expirationFirst=scenario.startsWith("expiration");
        if(collection) requests.approve(donor,r);
        Instant deadline=Instant.parse("2099-01-01T00:00:00Z");
        jdbc.update("update listings set collection_deadline=? where id=?",Timestamp.from(deadline),id);
        var sampled=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation->{
            int n=calls.incrementAndGet();
            if(n==1) {sampled.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}
            return Timestamp.from(deadline.plusSeconds(n==1&&!expirationFirst?-1:1));
        }).when(expirationSpy).decisionTime();
        Runnable action=()->{if(collection)handovers.completeByDonorOrAdmin(r,donor.id(),false);else requests.approve(donor,r);};
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{if(expirationFirst)expire(id);else action.run();});
            assertTrue(sampled.await(20,TimeUnit.SECONDS));
            var contenderStarted=new CountDownLatch(1);
            var second=pool.submit(()->{contenderStarted.countDown();if(expirationFirst)action.run();else expire(id);});
            assertTrue(contenderStarted.await(20,TimeUnit.SECONDS));release.countDown();first.get(30,TimeUnit.SECONDS);
            if(expirationFirst) {
                var failure=assertThrows(ExecutionException.class,()->second.get(30,TimeUnit.SECONDS));
                assertEquals("LISTING_EXPIRED",((RuleViolation)failure.getCause()).code());
            } else second.get(30,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertEquals(collection&&!expirationFirst?"COLLECTED":"EXPIRED",listings.get(id).status());
        assertEquals(collection&&!expirationFirst?0:1,count("select count(*) from activity where type='LISTING_EXPIRED'"));
    }
    @ParameterizedTest @ValueSource(strings={"edit-first","expire-edit","delete-first","expire-delete","cancel-first","expire-cancel","decline-first","expire-decline"})
    void otherMutationsSerializeWithExpiration(String scenario) throws Exception {
        UUID id=listing(),r=request(id);boolean expiryFirst=scenario.startsWith("expire");
        Instant deadline=Instant.parse("2099-01-01T00:00:00Z");
        jdbc.update("update listings set collection_deadline=? where id=?",Timestamp.from(deadline),id);
        var sampled=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation->{
            int n=calls.incrementAndGet();if(n==1){sampled.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}
            return Timestamp.from(deadline.plusSeconds(n==1&&!expiryFirst?-1:1));
        }).when(expirationSpy).decisionTime();
        Runnable action=()->{
            if(scenario.contains("edit")) listings.update(donor,id,new ListingOperations.CreateListing("Edited","Test","OTHER",BigDecimal.ONE,"ITEMS",null,"Lagos",Instant.parse("2100-01-01T00:00:00Z")));
            else if(scenario.contains("delete")) listings.delete(donor,id);
            else if(scenario.contains("cancel")) requests.cancel(recipient,r);
            else requests.decline(donor,r);
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{if(expiryFirst)expire(id);else action.run();});assertTrue(sampled.await(20,TimeUnit.SECONDS));
            var second=pool.submit(()->{if(expiryFirst)action.run();else expire(id);});release.countDown();first.get(30,TimeUnit.SECONDS);
            if(expiryFirst && !scenario.contains("delete")) {var failure=assertThrows(ExecutionException.class,()->second.get(30,TimeUnit.SECONDS));assertEquals("LISTING_EXPIRED",((RuleViolation)failure.getCause()).code());}
            else second.get(30,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertEquals(!expiryFirst&&scenario.contains("edit")?"AVAILABLE":scenario.contains("delete")?"DELETED":"EXPIRED",listings.get(id).status());
        if(!expiryFirst&&scenario.contains("cancel")) assertEquals("CANCELLED",requests.get(recipient,r).status());
        if(!expiryFirst&&scenario.contains("decline")) assertEquals("DECLINED",requests.get(recipient,r).status());
    }
}

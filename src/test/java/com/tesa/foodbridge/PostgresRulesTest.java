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
@Testcontainers
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PostgresRulesTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
        r.add("foodbridge.demo.seed-enabled",()->"false");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ListingOperations listings;
    @Autowired ReservationOperations requests;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    String bearer(String login) {
        String name=login.equals("admin1")?"admin":login;
        String password=switch(login) { case "donor1" -> "donor123"; case "recipient1" -> "recipient123"; default -> "admin123"; };
        try {
            var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                    .contentType("application/json")
                    .content("{\"email\":\""+name+"@demo.foodbridge.invalid\",\"password\":\""+password+"\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
            return "Bearer "+new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).get("token").asText();
        } catch(Exception e) { throw new AssertionError("Test login failed",e); }
    }
    Actor donor, recipient;
    @BeforeEach void setup() {
        jdbc.execute("truncate activity,handovers,requests,listings,users");
        donor=user(Actor.Role.DONOR); recipient=user(Actor.Role.RECIPIENT);
    }
    Actor user(Actor.Role role) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,?,?,?)",id,id+"@example.invalid","disabled","Simulated",role.name());
        return new Actor(id,role);
    }
    UUID listing() { return listings.create(donor,command(BigDecimal.TEN)).id(); }
    ListingOperations.CreateListing command(BigDecimal quantity) {
        return new ListingOperations.CreateListing("Simulated food","Test listing","BAKERY",quantity,"ITEMS",null,"Lagos",Instant.now().plusSeconds(3600));
    }
    UUID request(UUID listing) { return requests.request(recipient,listing,null).id(); }
    int count(String sql) { return jdbc.queryForObject(sql,Integer.class); }
    void code(String code,org.junit.jupiter.api.function.Executable call) {
        assertEquals(code,assertThrows(RuleViolation.class,call).code());
    }
    @Test void concurrentApprovalsHaveOneWinnerAndAtomicState() throws Exception {
        UUID listing=listing();
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<3;i++) ids.add(requests.request(user(Actor.Role.RECIPIENT),listing,null).id());
        var results=parallel(ids,id->{
            try { requests.approve(donor,id); return "SUCCESS"; }
            catch(RuleViolation e) { return e.code(); }
        });
        assertEquals(1,Collections.frequency(results,"SUCCESS"));
        assertEquals(2,Collections.frequency(results,"LISTING_ALREADY_RESERVED"));
        assertEquals(1,count("select count(*) from requests where status='APPROVED'"));
        assertEquals(2,count("select count(*) from requests where status='PENDING' and decided_at is null"));
        assertEquals(1,count("select count(*) from handovers where status='AWAITING_COLLECTION'"));
        assertEquals(1,count("select count(*) from activity where type='REQUEST_APPROVED'"));
        assertEquals(1,count("select count(*) from listings where status='RESERVED'"));
        System.out.println("SLIDE4 SERVICE: approvals=1 conflicts=2 handovers=1 atomic state verified");
    }
    @Test void duplicateApprovalIsConflict() {
        UUID id=request(listing()); requests.approve(donor,id);
        code("LISTING_ALREADY_RESERVED",()->requests.approve(donor,id));
        assertEquals(1,count("select count(*) from handovers"));
    }
    @Test void expiredListingsRejectRequestsAndApprovals() {
        UUID listing=listing(), id=request(listing);
        jdbc.update("update listings set collection_deadline=clock_timestamp()-interval '1 second' where id=?",listing);
        code("LISTING_EXPIRED",()->request(listing));
        code("LISTING_EXPIRED",()->requests.approve(donor,id));
        assertEquals(0,count("select count(*) from handovers"));
    }
    @Test void approvalChecksDeadlineAfterWaitingForListingLock() throws Exception {
        UUID listing=listing(), id=request(listing);
        try(var conn=DB.createConnection("")) {
            conn.setAutoCommit(false);
            try(var stmt=conn.prepareStatement("update listings set collection_deadline=clock_timestamp()+interval '1 second' where id=?")) {
                stmt.setObject(1,listing); stmt.executeUpdate();
            }
            try(var pool=Executors.newSingleThreadExecutor()) {
                var task=pool.submit(()->{ requests.approve(donor,id); return "SUCCESS"; });
                // Hold the lock until the deadline is in the past, then let approval acquire it.
                try(var stmt=conn.createStatement()) { stmt.execute("select pg_sleep(1.2)"); }
                conn.commit();
                var failure=assertThrows(ExecutionException.class,()->task.get(15,TimeUnit.SECONDS));
                assertEquals("LISTING_EXPIRED",((RuleViolation)failure.getCause()).code());
            }
        }
    }
    @Test void unauthorizedApprovalsAreRejected() {
        UUID id=request(listing());
        Actor stranger=user(Actor.Role.DONOR);
        code("FORBIDDEN",()->requests.approve(stranger,id));
        code("FORBIDDEN",()->requests.approve(recipient,id));
        code("FORBIDDEN",()->requests.approve(new Actor(recipient.id(),Actor.Role.ADMIN),id));
        requests.approve(user(Actor.Role.ADMIN),id);
    }
    @Test void invalidQuantitiesRejectedByServiceAndDatabase() {
        code("INVALID_LISTING",()->listings.create(donor,command(BigDecimal.ZERO)));
        UUID id=listing();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->jdbc.update("update listings set quantity=0 where id=?",id));
    }
    @Test void moduleContractsEnforceCreationAndReadAccess() {
        code("FORBIDDEN",()->listings.create(recipient,command(BigDecimal.ONE)));
        UUID listing=listing();
        code("FORBIDDEN",()->requests.request(donor,listing,null));
        UUID id=request(listing);
        assertEquals(id,requests.get(donor,id).id());
        Actor other=user(Actor.Role.RECIPIENT);
        code("FORBIDDEN",()->requests.get(other,id));
        code("INVALID_PAGE",()->listings.browse(0,101));
        assertEquals(1,listings.browse(0,20).size());
    }
    @Test void idempotencyIsAtomicAndBoundToActorAndListing() throws Exception {
        UUID listing=listing();
        var ids=parallel(List.of(1,2,3),i->requests.request(recipient,listing,"same-key").id());
        assertEquals(1,new HashSet<>(ids).size());
        assertEquals(1,count("select count(*) from requests"));
        Actor other=user(Actor.Role.RECIPIENT);
        code("IDEMPOTENCY_KEY_REUSED",()->requests.request(other,listing,"same-key"));
        UUID another=listing();
        code("IDEMPOTENCY_KEY_REUSED",()->requests.request(recipient,another,"same-key"));
    }
    @Test void failedHandoverRollsBackApprovalAndListing() {
        UUID listing=listing(), id=request(listing);
        // Deliberately inject a downstream failure; the entire approval must roll back.
        jdbc.update("insert into handovers(id,request_id) values (?,?)",UUID.randomUUID(),id);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->requests.approve(donor,id));
        assertEquals("PENDING",requests.get(recipient,id).status());
        assertEquals("AVAILABLE",listings.get(listing).status());
        assertEquals(0,count("select count(*) from activity where type='REQUEST_APPROVED'"));
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
        for(String status:List.of("RESERVED","COLLECTED","REVERSED","EXPIRED")) {
            UUID id=listings.create(new Actor(owner,Actor.Role.DONOR),command(BigDecimal.ONE)).id();
            jdbc.update("update listings set status=? where id=?",status,id);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/listings/"+id).header("Authorization",bearer("donor1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            assertEquals(status,listings.get(id).status());
        }
    }
    static <T,R> List<R> parallel(List<T> values,java.util.function.Function<T,R> operation) throws Exception {
        var ready=new CountDownLatch(values.size()); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(values.size())) {
            List<Future<R>> futures=new ArrayList<>();
            for(T value:values) futures.add(pool.submit(()->{
                ready.countDown(); if(!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return operation.apply(value);
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            List<R> results=new ArrayList<>();
            for(var future:futures) results.add(future.get(30,TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); }
    }
}

package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tesa.foodbridge.shared.Actor;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Scenario recipe for the existing maintenance command, not a runner or Spring component. */
final class PresentationLifecycleScenarios {
    static final String VERSION = "presentation-lifecycle-v1";
    static final String DATABASE = "foodbridge_presentation_disposable";
    static final UUID MANIFEST = DemoSeed.id(VERSION + "/manifest");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TABLES = List.of("users", "listings", "requests", "handovers", "activity");
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final DemoSeedDataset dataset = DemoSeedDataset.load();
    private final PresentationSeedMaintenance.LifecycleOperations operations;

    PresentationLifecycleScenarios(JdbcTemplate jdbc, Environment env,
                                   PresentationSeedMaintenance.LifecycleOperations operations) {
        this.jdbc = jdbc; this.env = env; this.operations = operations;
    }

    static UUID requestId(int number, String slot) { return DemoSeed.id(VERSION + "/request/" + number + "/" + slot); }
    static UUID handoverId(int number) { return DemoSeed.id(VERSION + "/handover/" + number); }
    static UUID activityId(int number, String step) { return DemoSeed.id(VERSION + "/activity/" + number + "/" + step); }
    private UUID listingId(int number) { return dataset.listingIds().get(number - 1); }
    private UUID donorId(int number) { return DemoSeed.uuid(dataset.root().get("listings").get(number - 1), "donor_id"); }

    static ObjectNode plan() {
        var plan = JSON.createObjectNode();
        plan.put("version", VERSION);
        plan.put("existing_installations", "Read-only plan. Applying scenarios to an existing installation is unsupported; a separate future gate/review is required.");
        plan.put("fresh_target", "Explicit disposable loopback database only: " + DATABASE);
        plan.put("historical_fixture_origin", "HISTORICAL_FIXTURE_NOT_COMMAND_EXECUTION");
        plan.put("chronology_recipe", "Identities A-5d; listings A-4d; current requests A-2h; approvals A; decline/cancel A-1h; pending expiry requests A-71h/deadline A-68h; reservation request A-47h/approval A-46h/deadline A-45h; archive-expired deadline A-24h. Domain processing times are captured, not invented.");
        String[] names = {"AVAILABLE_NO_REQUESTS", "AVAILABLE_PENDING", "AVAILABLE_DECLINED",
                "RESERVED_APPROVED_WITH_DECLINED_COMPETITORS", "COLLECTED", "REVERSED_HANDOVER_REOPENED_LISTING",
                "RECIPIENT_CANCELLED", "EXPIRED_NO_REQUESTS", "EXPIRED_WHILE_PENDING_A",
                "EXPIRED_WHILE_PENDING_B", "EXPIRED_RESERVATION", "DELETED_AVAILABLE", "ARCHIVED_EXPIRED"};
        var rows = plan.putArray("listings");
        var listings = DemoSeedDataset.load().root().get("listings");
        for (int n = 1; n <= 40; n++) {
            var row = rows.addObject(); row.put("listing_id", listings.get(n - 1).path("id").asText());
            row.put("scenario", n <= names.length ? names[n - 1] : names[0]);
            if (Set.of(2,3,4,5,6,7,9,10,11).contains(n)) row.put("request_id", requestId(n,"primary").toString());
            if (Set.of(4,5,6,11).contains(n)) row.put("handover_id", handoverId(n).toString());
            if (n == 4) {
                row.putArray("competitor_request_ids").add(requestId(4,"competitor-2").toString())
                        .add(requestId(4,"competitor-3").toString());
            }
        }
        return plan;
    }

    PresentationSeedMaintenance.Preview preview() {
        var state = snapshot();
        var output = JSON.createObjectNode();
        output.set("plan", plan()); output.put("dataset_sha256", dataset.sha256());
        output.set("current_records", state);
        output.set("target_identity",jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<ObjectNode>) connection -> {
            URI uri=URI.create(connection.getMetaData().getURL().replaceFirst("^jdbc:",""));
            var identity=JSON.createObjectNode();
            identity.put("host",uri.getHost());identity.put("port",uri.getPort());
            identity.put("database",connection.getCatalog());identity.put("schema",connection.getSchema());
            identity.put("database_role",connection.getMetaData().getUserName());
            return identity; // Never include URL query, user-info, passwords or connection properties.
        }));
        output.set("schema", read("select version,checksum from flyway_schema_history where success order by installed_rank"));
        output.set("constraints",read("select conname,conrelid::regclass::text as relation,convalidated,pg_get_constraintdef(oid) as definition from pg_constraint where connamespace='public'::regnamespace"));
        output.put("disposable_loopback_target", isDisposableTarget());
        var blockers = new ArrayList<String>();
        var changes = output.putArray("progress_or_extra_records");
        JsonNode manifest = DemoSeed.find(state.get("activity"), MANIFEST.toString());
        if (manifest == null) {
            boolean empty = TABLES.stream().allMatch(table -> state.get(table).isEmpty());
            output.put("disposition", empty ? "FRESH_INSTALL_PLAN" : "EXISTING_INSTALLATION_READ_ONLY");
            if (!empty) blockers.add("Existing operational records have no lifecycle manifest. No scenario overwrite, repair or reset is permitted.");
        } else {
            output.put("disposition", "INSTALLED_NO_WRITES");
            try {
                JsonNode details = JSON.readTree(manifest.path("details").asText());
                if (!details.path("version").asText().equals(VERSION)
                        || !details.path("definition_sha256").asText().equals(definitionHash())
                        || !details.path("baseline_sha256").asText().equals(DemoSeed.digest(details.path("baseline").toString()))
                        || !manifest.path("type").asText().equals("LISTING_CREATED")
                        || !manifest.path("listing_id").asText().equals(listingId(1).toString())
                        || !manifest.path("actor_id").asText().equals(donorId(1).toString())
                        || !details.path("record_kind").asText().equals("INSTALLATION_MANIFEST_NOT_A_LISTING_COMMAND")
                        || !Instant.parse(manifest.path("created_at").asText()).equals(Instant.parse(details.path("anchor").asText()))) {
                    throw new IllegalStateException("Manifest identity/definition/baseline conflict");
                }
                output.put("anchor", details.path("anchor").asText());
                Instant.parse(details.path("anchor").asText());
                var mutable = Map.of(
                        "users", Set.<String>of(),
                        "listings", Set.of("status","updated_at","expired_at","expiration_reason"),
                        "requests", Set.of("status","decided_at","expired_at","expiration_reason"),
                        "handovers", Set.of("status","completed_at","reversed_at","expired_at","expiration_reason"),
                        "activity", Set.<String>of());
                for (String table : TABLES) {
                    JsonNode baseline = details.path("baseline").path(table);
                    for (JsonNode before : baseline) {
                        JsonNode now = DemoSeed.find(state.get(table), before.path("id").asText());
                        if (now == null) { blockers.add("Missing seeded " + table + " " + before.path("id").asText()); continue; }
                        ObjectNode original = ((ObjectNode)before).deepCopy(), current = ((ObjectNode)now).deepCopy();
                        mutable.get(table).forEach(field -> { original.remove(field); current.remove(field); });
                        if (!original.equals(current)) blockers.add("Modified immutable seeded " + table + " " + before.path("id").asText());
                        else if (!before.equals(now)) changes.add(table + " " + before.path("id").asText() + " progressed/modified; retained without writes");
                    }
                    for (JsonNode now : state.get(table)) {
                        if (table.equals("activity") && now.path("id").asText().equals(MANIFEST.toString())) continue;
                        if (DemoSeed.find(baseline,now.path("id").asText()) == null)
                            changes.add("Additional " + table + " " + now.path("id").asText() + "; retained without writes");
                    }
                }
            } catch (Exception e) { blockers.add("Invalid lifecycle manifest: " + e.getMessage()); }
        }
        if (!output.path("disposable_loopback_target").asBoolean()) blockers.add("Apply requires the dedicated disposable database on a loopback JDBC URL.");
        if (!jdbc.queryForObject("select exists(select 1 from flyway_schema_history where version='8' and success)",Boolean.class))
            blockers.add("Migrate the disposable database through V8 before installation.");
        return new PresentationSeedMaintenance.Preview(DemoSeed.digest(output.toString()),output,List.copyOf(blockers));
    }

    void apply(String token) {
        if (!env.acceptsProfiles(Profiles.of("demo & !prod & !production"))
                || !env.getProperty("foodbridge.presentation.maintenance-enabled",Boolean.class,false)
                || !env.getProperty("foodbridge.presentation.lifecycle-disposable-enabled",Boolean.class,false))
            throw new IllegalStateException("Explicit demo maintenance and lifecycle-disposable enable flags are required.");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("A writable Spring transaction is required.");
        if (token == null || !token.matches("[0-9a-f]{64}")) throw new IllegalStateException("Reviewed lifecycle preview token required.");
        if (!isDisposableTarget()) throw new IllegalStateException("Only the dedicated loopback disposable database is permitted.");
        jdbc.execute("set local lock_timeout='5s'");
        jdbc.execute("lock table users,listings,requests,handovers,activity in share row exclusive mode");
        var current = preview();
        if (!current.blockers().isEmpty()) throw new IllegalStateException("Lifecycle installation blocked: " + current.blockers());
        JsonNode marker = DemoSeed.find(current.affectedRecords().path("current_records").path("activity"),MANIFEST.toString());
        if (marker != null) {
            try {
                String originalToken = JSON.readTree(marker.path("details").asText()).path("approved_preview").asText();
                if (!token.equals(originalToken) && !token.equals(current.approvalToken())) throw new IllegalStateException("Unreviewed rerun token.");
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            return; // Even valid operational progress is reported, never reset.
        }
        if (!token.equals(current.approvalToken())) throw new IllegalStateException("Stale lifecycle preview; no writes permitted.");
        Objects.requireNonNull(operations,"Domain operations required for fresh lifecycle installation");
        Instant anchor = jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();
        createIdentities(anchor);
        createListings(anchor);
        pending(2,"primary",1,anchor.minusSeconds(7200));
        pending(3,"primary",1,anchor.minusSeconds(7200));
        resolveFixture(3,"primary","DECLINED",anchor.minusSeconds(3600),donorId(3),"DONOR_DECLINE_FIXTURE");
        for (int n : List.of(4,5,6)) {
            int recipient = n - 3;
            pending(n,"primary",recipient,anchor.minusSeconds(7200));
            if (n == 4) {
                pending(4,"competitor-2",2,anchor.minusSeconds(7200));
                pending(4,"competitor-3",3,anchor.minusSeconds(7200));
            }
            // Enforce the real fairness rule; one first-round approval per recipient.
            operations.distribution().assertRecipientEligibleForApproval(DemoSeed.id("recipient" + recipient));
            approvedFixture(n,anchor);
            if (n == 4) for (int competitor : List.of(2,3))
                resolveFixture(4,"competitor-" + competitor,"DECLINED",anchor,donorId(4),"APPROVAL_AUTO_DECLINE_FIXTURE");
        }
        for (int n : List.of(5,6)) command(n,"collect","COLLECTED",
                () -> operations.handovers().completeByDonorOrAdmin(requestId(n,"primary"),donorId(n),false));
        command(6,"reverse","REVERSED",() -> operations.handovers().reverseByDonorOrAdmin(handoverId(6),donorId(6),false));
        pending(7,"primary",1,anchor.minusSeconds(7200));
        resolveFixture(7,"primary","CANCELLED",anchor.minusSeconds(3600),DemoSeed.id("recipient1"),"RECIPIENT_CANCEL_FIXTURE");
        for (int n : List.of(9,10)) pending(n,"primary",n == 9 ? 2 : 3,anchor.minusSeconds(3*86400-3600));
        pending(11,"primary",1,anchor.minusSeconds(2*86400-3600));
        approvedFixture(11,anchor.minusSeconds(2*86400-7200)); // One approval on a separate historical day.
        for (int n : List.of(8,9,10,11,13)) command(n,"expire","LISTING_EXPIRED",() -> operations.expiration().expireLocked(listingId(n)));
        for (int n : List.of(12,13)) command(n,"archive","LISTING_ARCHIVED",
                () -> operations.listings().delete(new Actor(donorId(n),Actor.Role.DONOR),listingId(n)));
        ObjectNode baseline = snapshot();
        var manifest = JSON.createObjectNode(); manifest.put("version",VERSION); manifest.put("record_kind","INSTALLATION_MANIFEST_NOT_A_LISTING_COMMAND");
        manifest.put("anchor",anchor.toString()); manifest.put("approved_preview",token);
        manifest.put("definition_sha256",definitionHash()); manifest.set("baseline",baseline);
        manifest.put("baseline_sha256",DemoSeed.digest(baseline.toString()));
        event(MANIFEST,donorId(1),listingId(1),"LISTING_CREATED",manifest,anchor);
        var result = preview();
        if (!result.blockers().isEmpty()) throw new IllegalStateException("Lifecycle postcondition failed: " + result.blockers());
    }

    private void createIdentities(Instant anchor) {
        for (String name : List.of("donor1","donor2","recipient1","recipient2","recipient3","admin")) {
            String role = name.startsWith("donor") ? "DONOR" : name.startsWith("recipient") ? "RECIPIENT" : "ADMIN";
            JsonNode donor = DemoSeed.find(dataset.root().get("donors"),DemoSeed.id(name).toString());
            String displayName = donor == null ? name + " (Disposable Demo)" : donor.path("display_name").asText();
            jdbc.update("insert into users(id,email,password_hash,display_name,role,created_at) values (?,?,'disabled-presentation-fixture',?,?,?)",
                    DemoSeed.id(name),name + "@demo.foodbridge.invalid",displayName,role,Timestamp.from(anchor.minusSeconds(5*86400)));
        }
    }
    private void createListings(Instant anchor) {
        for (int n = 1; n <= 40; n++) {
            JsonNode row = dataset.root().get("listings").get(n - 1);
            Instant deadline = switch (n) {
                case 8,9,10 -> anchor.minusSeconds(3*86400-4*3600);
                case 11 -> anchor.minusSeconds(2*86400-3*3600);
                case 13 -> anchor.minusSeconds(86400);
                default -> DemoSeedDataset.collectionDeadline(row,anchor);
            };
            Instant created = anchor.minusSeconds(4*86400);
            jdbc.update("""
                    insert into listings(id,donor_id,title,description,category,quantity,unit,image_url,pickup_area,
                    collection_deadline,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,'AVAILABLE',?,?)
                    """,listingId(n),donorId(n),row.path("title").asText(),row.path("description").asText(),row.path("category").asText(),
                    row.path("quantity").decimalValue(),row.path("unit").asText(),row.path("image_url").isNull()?null:row.path("image_url").asText(),
                    row.path("pickup_area").asText(),Timestamp.from(deadline),Timestamp.from(created),Timestamp.from(created));
            fixtureEvent(n,"listing-created",donorId(n),"LISTING_CREATED","HISTORICAL_LISTING_ROW",created,null);
        }
    }
    private void pending(int n, String slot, int recipient, Instant created) {
        UUID id = requestId(n,slot);
        jdbc.update("insert into requests(id,listing_id,requester_id,idempotency_key,created_at) values (?,?,?,?,?)",
                id,listingId(n),DemoSeed.id("recipient"+recipient),VERSION+"/"+n+"/"+slot,Timestamp.from(created));
        fixtureEvent(n,"request-created-"+slot,DemoSeed.id("recipient"+recipient),"REQUEST_CREATED","HISTORICAL_PENDING_REQUEST",created,id);
    }
    private void approvedFixture(int n, Instant approved) {
        resolveFixture(n,"primary","APPROVED",approved,donorId(n),"HISTORICAL_APPROVAL");
        jdbc.update("update listings set status='RESERVED',updated_at=? where id=?",Timestamp.from(approved),listingId(n));
        jdbc.update("insert into handovers(id,request_id,status) values (?,?,'AWAITING_COLLECTION')",handoverId(n),requestId(n,"primary"));
    }
    private void resolveFixture(int n, String slot, String status, Instant time, UUID actor, String origin) {
        jdbc.update("update requests set status=?,decided_at=? where id=? and status='PENDING'",status,Timestamp.from(time),requestId(n,slot));
        fixtureEvent(n,"decision-"+slot,actor,"REQUEST_"+status,origin,time,requestId(n,slot));
    }
    private void fixtureEvent(int n, String step, UUID actor, String type, String explanation, Instant time, UUID request) {
        var details = JSON.createObjectNode(); details.put("origin","HISTORICAL_FIXTURE_NOT_COMMAND_EXECUTION");
        details.put("fixture",VERSION); details.put("explanation",explanation);
        if (request != null) details.put("requestId",request.toString());
        event(activityId(n,step),actor,listingId(n),type,details,time);
    }
    private void command(int n, String step, String type, Runnable action) {
        var before = new HashSet<>(jdbc.queryForList("select id from activity where listing_id=?",UUID.class,listingId(n)));
        action.run();
        var added = jdbc.queryForList("select * from activity where listing_id=?",listingId(n)).stream()
                .filter(row -> !before.contains(row.get("id"))).toList();
        if (added.size() != 1 || !added.getFirst().get("type").equals(type)) throw new IllegalStateException("Unexpected domain activity for " + step);
        var row = added.getFirst();
        Instant processed = switch (step) {
            case "collect" -> jdbc.queryForObject("select completed_at from handovers where id=?",Timestamp.class,handoverId(n)).toInstant();
            case "reverse" -> jdbc.queryForObject("select reversed_at from handovers where id=?",Timestamp.class,handoverId(n)).toInstant();
            case "expire" -> jdbc.queryForObject("select expired_at from listings where id=?",Timestamp.class,listingId(n)).toInstant();
            default -> jdbc.queryForObject("select updated_at from listings where id=?",Timestamp.class,listingId(n)).toInstant();
        };
        try {
            var details = (ObjectNode)JSON.readTree(row.get("details").toString());
            var provenance = details.putObject("presentation_fixture"); provenance.put("version",VERSION);
            provenance.put("origin","DOMAIN_OPERATION_EXECUTED"); provenance.put("operation",step);
            provenance.put("original_activity_created_at",((Timestamp)row.get("created_at")).toInstant().toString());
            provenance.put("processing_time",processed.toString());
            // Deterministic identity and actual processing time for this installation's new event only.
            // Retain the service's transaction-start activity time above; never rewrite existing events.
            jdbc.update("update activity set id=?,details=?,created_at=? where id=?",activityId(n,step),details.toString(),Timestamp.from(processed),row.get("id"));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private void event(UUID id, UUID actor, UUID listing, String type, ObjectNode details, Instant time) {
        jdbc.update("insert into activity(id,actor_id,listing_id,type,details,created_at) values (?,?,?,?,?,?)",id,actor,listing,type,details.toString(),Timestamp.from(time));
    }
    private String definitionHash() { return DemoSeed.digest(dataset.sha256() + plan()); }
    private ObjectNode snapshot() {
        var result = JSON.createObjectNode();
        for (String table : TABLES) result.set(table,read(table.equals("users")
                ? "select id,email,display_name,role,created_at from users" : "select * from " + table));
        return result;
    }
    private JsonNode read(String sql) {
        try { return JSON.readTree(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from ("+sql+") t",String.class)); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private boolean isDisposableTarget() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try { validateTarget(connection.getMetaData().getURL());
                return DATABASE.equals(connection.getCatalog()) && "public".equals(connection.getSchema()); }
            catch (IllegalArgumentException e) { return false; }
        }));
    }
    static void validateTarget(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Explicit PostgreSQL JDBC URL required");
        URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:",""));
        if (!"postgresql".equals(uri.getScheme()) || uri.getHost()==null || !Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost())
                || !Objects.equals(uri.getPath(),"/"+DATABASE) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Lifecycle provisioning requires a query-free loopback JDBC URL for " + DATABASE);
    }
}

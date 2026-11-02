package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.IntStream;

/** Startup may only preview. Mutations belong to the separately enabled manual maintenance command. */
@Component
@Profile("demo & !prod & !production")
@ConditionalOnProperty(name="foodbridge.demo.seed-enabled", havingValue="true")
public class DemoSeed implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DemoSeed.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROPERTY = "foodbridge.demo.";
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final DemoSeedDataset dataset;

    public DemoSeed(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.dataset = DemoSeedDataset.load();
    }
    public static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("foodbridge-simulated-" + key).getBytes(StandardCharsets.UTF_8));
    }
    public record Preview(String approvalToken, JsonNode affectedRecords, List<String> blockers) {}

    /** Read only. Credentials are excluded; approval covers database identity, dataset and scoped records. */
    @Transactional(readOnly=true)
    public Preview preview() {
        var snapshot = JSON.createObjectNode();
        snapshot.put("dataset_sha256", dataset.sha256());
        snapshot.set("database", read("select current_database() as database, current_schema() as schema, "
                + "inet_server_addr()::text as server_address, inet_server_port() as server_port"));
        String legacy = ids(legacyIds()), requests = ids(expectedRequests().keySet());
        snapshot.set("legacy_listings", read("select * from listings where id in (" + legacy + ")"));
        snapshot.set("requests", read("select * from requests where listing_id in (" + legacy + ") or id in (" + requests + ")"));
        snapshot.set("handovers", read("select * from handovers where request_id in "
                + "(select id from requests where listing_id in (" + legacy + ") or id in (" + requests
                + ")) or id in (" + ids(List.of(id("handover5"), id("handover6"), id("handover7"))) + ")"));
        snapshot.set("activity", read("select * from activity where listing_id in (" + legacy + ")"));
        snapshot.set("replacement_listings", read("select * from listings where id in (" + ids(dataset.listingIds()) + ")"));
        String users = ids(List.of(id("donor1"), id("donor2"), id("recipient1"), id("recipient2"), id("recipient3"), id("admin")));
        snapshot.set("users", read("select id,email,display_name,role,created_at from users where id in (" + users
                + ") or email in ('donor1@demo.foodbridge.invalid','donor2@demo.foodbridge.invalid')"));
        snapshot.set("proposed_donors", dataset.root().get("donors"));
        snapshot.set("proposed_listings", dataset.root().get("listings"));
        return new Preview(digest(snapshot.toString()), snapshot, List.copyOf(inspect(snapshot)));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!environment.acceptsProfiles(Profiles.of("demo & !prod & !production"))
                || !environment.getProperty(PROPERTY + "seed-enabled",Boolean.class,false))
            throw new IllegalStateException("Demo seed requires demo profile, seed-enabled=true, and neither prod nor production.");
        if (!environment.getProperty(PROPERTY+"seed-mode","preview").equals("preview"))
            throw new IllegalStateException("Startup replacement is disabled. Use the explicitly enabled PresentationSeedCommand after backup and preview approval.");
        display(preview());
    }

    private List<String> inspect(ObjectNode snapshot) {
        var blockers = new ArrayList<String>();
        JsonNode users = snapshot.get("users");
        for (JsonNode donor : dataset.root().get("donors")) {
            for (JsonNode user : users) {
                if (user.path("id").equals(donor.path("id")) || user.path("email").equals(donor.path("email"))) {
                    if (!same(user,donor,"id","email","role")) blockers.add("Donor identity conflict: " + user.path("id").asText());
                    String name = user.path("display_name").asText();
                    if (!name.equals("SIMULATED " + donor.path("seed_key").asText()) && !name.equals(donor.path("display_name").asText()))
                        blockers.add("Demo donor name changed; review account ownership: " + user.path("id").asText());
                }
            }
        }
        for (JsonNode listing : snapshot.get("legacy_listings")) {
            int number = IntStream.rangeClosed(1,12).filter(i -> id("listing"+i).toString().equals(listing.path("id").asText())).findFirst().orElseThrow();
            String donorKey = number % 2 == 0 ? "donor2" : "donor1";
            String title = "SIMULATED " + (number == 10 ? "Community lunch surplus with assorted vegetables and rice for multiple recipient organizations collecting at the designated afternoon pickup point " : "Meal donation ") + number;
            String status = switch(number) { case 5 -> "RESERVED"; case 6 -> "COLLECTED"; case 7 -> "REVERSED"; case 11,12 -> "EXPIRED"; default -> "AVAILABLE"; };
            if (!listing.path("donor_id").asText().equals(id(donorKey).toString()) || !listing.path("title").asText().equals(title)
                    || !listing.path("description").asText().equals("Fictional demonstration listing; no real food is offered.")
                    || !listing.path("category").asText().equals("PREPARED_MEALS")
                    || listing.path("quantity").decimalValue().compareTo(java.math.BigDecimal.valueOf(20)) != 0
                    || !listing.path("unit").asText().equals("PORTIONS")
                    || !Objects.equals(text(listing,"image_url"), number == 9 ? null : "https://example.invalid/simulated-food.jpg")
                    || !listing.path("pickup_area").asText().equals("SIMULATED Lagos pickup") || !listing.path("status").asText().equals(status))
                blockers.add("Legacy listing differs from original fixture: " + listing.path("id").asText() + ". Preserve and resolve manually.");
            requireDemoUser(users,donorKey,"DONOR",blockers);
        }
        var expected = expectedRequests();
        for (JsonNode request : snapshot.get("requests")) {
            JsonNode baseline = expected.get(uuid(request,"id"));
            if (baseline == null || !same(request,baseline,"listing_id","requester_id","status","idempotency_key"))
                blockers.add("Unexpected or changed request: " + request.path("id").asText() + ". It will not be deleted.");
            else requireDemoUser(users,baseline.path("requester_key").asText(),"RECIPIENT",blockers);
        }
        for (JsonNode handover : snapshot.get("handovers")) {
            int number = IntStream.rangeClosed(5,7).filter(i -> id("handover"+i).toString().equals(handover.path("id").asText())).findFirst().orElse(0);
            String status = number == 5 ? "AWAITING_COLLECTION" : number == 6 ? "COLLECTED" : "REVERSED";
            if (number == 0 || !handover.path("request_id").asText().equals(id("request"+number+"-1").toString())
                    || !handover.path("status").asText().equals(status) || handover.path("portions_estimate").asInt() != 20)
                blockers.add("Unexpected or changed handover: " + handover.path("id").asText() + ". It will not be deleted.");
        }
        for (JsonNode activity : snapshot.get("activity"))
            blockers.add("Activity references legacy listing: " + activity.path("id").asText() + ". Original seeder created none; preserve and resolve manually.");
        for (JsonNode existing : snapshot.get("replacement_listings")) {
            JsonNode proposed = find(dataset.root().get("listings"),existing.path("id").asText());
            if (!same(existing,proposed,"donor_id","title","description","category","unit","image_url","pickup_area")
                    || existing.path("quantity").decimalValue().compareTo(proposed.path("quantity").decimalValue()) != 0)
                blockers.add("Replacement ID contains different content: " + existing.path("id").asText() + ". It will not be overwritten.");
        }
        int count = snapshot.get("replacement_listings").size();
        if (count != 0 && count != 40) blockers.add("Partial replacement dataset ("+count+"/40). Review manually; automatic repair could resurrect a removed listing.");
        return blockers;
    }
    private static void requireDemoUser(JsonNode users, String key, String role, List<String> blockers) {
        JsonNode user = find(users,id(key).toString());
        if (user == null || !user.path("email").asText().equals(key+"@demo.foodbridge.invalid") || !user.path("role").asText().equals(role))
            blockers.add("Fixture user identity mismatch: " + key);
    }
    private static Map<UUID,JsonNode> expectedRequests() {
        var expected = new LinkedHashMap<UUID,JsonNode>();
        for (int i : new int[]{2,3,4,5,6,7}) {
            for (int r=1; r<=(i==3?3:1); r++) {
                var node = JSON.createObjectNode();
                node.put("listing_id",id("listing"+i).toString()); node.put("requester_id",id("recipient"+r).toString());
                node.put("requester_key","recipient"+r); node.put("status",i>=5?"APPROVED":"PENDING");
                node.put("idempotency_key","simulated-"+i+"-"+r);
                expected.put(id("request"+i+"-"+r),node);
            }
        }
        return expected;
    }
    private JsonNode read(String select) {
        // JSONB provides stable keys and database timestamp representations for snapshot approval.
        String sql = "select coalesce(jsonb_agg(to_jsonb(scope) order by to_jsonb(scope)::text),'[]'::jsonb)::text from ("+select+") scope";
        try { return JSON.readTree(jdbc.queryForObject(sql,String.class)); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read demo preview",e); }
    }
    private static void display(Preview preview) {
        // One record per log event avoids truncating a single large structured logging event.
        LOG.info("Demo seed preview (no credentials): database={} dataset_sha256={}",
                preview.affectedRecords().get("database"),preview.affectedRecords().get("dataset_sha256"));
        for (String section : List.of("users","legacy_listings","requests","handovers","activity",
                "replacement_listings","proposed_donors","proposed_listings")) {
            JsonNode rows = preview.affectedRecords().get(section);
            LOG.info("Demo seed preview section={} count={}",section,rows.size());
            for (JsonNode row : rows) LOG.info("Demo seed preview section={} record={}",section,row);
        }
        LOG.info("Demo seed preview blockers={} approvalToken={}",preview.blockers(),preview.approvalToken());
    }
    private static List<UUID> legacyIds() { return IntStream.rangeClosed(1,12).mapToObj(i -> id("listing"+i)).toList(); }
    private static String ids(Collection<UUID> ids) { return String.join(",",ids.stream().map(value -> "'"+value+"'::uuid").toList()); }
    static UUID uuid(JsonNode node, String field) { return UUID.fromString(node.path(field).asText()); }
    private static String text(JsonNode node, String field) { return node.path(field).isNull()?null:node.path(field).asText(); }
    static JsonNode find(JsonNode rows, String id) {
        for (JsonNode row : rows) if (row.path("id").asText().equals(id)) return row;
        return null;
    }
    private static boolean same(JsonNode a, JsonNode b, String... fields) {
        for (String field : fields) if (!Objects.equals(a.get(field),b.get(field))) return false;
        return true;
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

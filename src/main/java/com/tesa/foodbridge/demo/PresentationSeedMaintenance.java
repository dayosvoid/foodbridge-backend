package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Manual administration only: deliberately not a Spring component, runner, controller or initializer. */
public final class PresentationSeedMaintenance {
    private static final ObjectMapper JSON=new ObjectMapper();
    static final String OPERATION="foodbridge-presentation-soft-delete-v4-v1";
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final BackupVerifier backupVerifier;
    private final DemoSeedDataset dataset=DemoSeedDataset.load();
    private final PresentationAuditJournal journal;

    public PresentationSeedMaintenance(JdbcTemplate jdbc,Environment env) {
        this(jdbc,env,new BackupVerifier());
    }
    PresentationSeedMaintenance(JdbcTemplate jdbc,Environment env,BackupVerifier backupVerifier) {
        this.jdbc=jdbc; this.env=env; this.backupVerifier=backupVerifier;
        journal=new PresentationAuditJournal(Path.of(env.getProperty("foodbridge.presentation.audit-directory","backups/audits")),
                env.getProperty("foodbridge.presentation.expected-host",""));
    }
    public record Preview(String approvalToken,JsonNode affectedRecords,List<String> blockers) {}

    public Preview preview() {
        ObjectNode snapshot=(ObjectNode)new DemoSeed(jdbc,env).preview().affectedRecords().deepCopy();
        snapshot.put("operation",OPERATION);
        snapshot.put("audit_strategy","Immutable external before-image journal; no activity rows are inserted or changed.");
        snapshot.put("audit_journal",journal.path().toString());
        snapshot.put("backup_required",true);
        snapshot.put("backup_status","Not verified by preview; apply requires a token-bound archive and rechecks its integrity.");
        snapshot.put("donor_policy","Reuse existing users unchanged; do not rename or provision accounts.");
        var proposedDonors=JSON.createArrayNode();
        for(JsonNode donor:dataset.root().get("donors")) {
            JsonNode existing=DemoSeed.find(snapshot.get("users"),donor.path("id").asText());
            if(existing!=null) proposedDonors.add(existing.deepCopy());
        }
        snapshot.set("proposed_donors",proposedDonors);
        var archivePlan=snapshot.putArray("archive_plan");
        for(JsonNode legacy:snapshot.get("legacy_listings")) {
            var action=archivePlan.addObject();action.put("listing_id",legacy.path("id").asText());
            action.put("current_status",legacy.path("status").asText());action.put("target_status","DELETED");
            action.put("will_change",!legacy.path("status").asText().equals("DELETED"));
        }
        snapshot.put("expected_host",env.getProperty("foodbridge.presentation.expected-host",""));
        String constraint=jdbc.queryForObject("select coalesce((select pg_get_constraintdef(oid) from pg_constraint "
                +"where conrelid='listings'::regclass and conname='listings_status_check'),'')",String.class);
        snapshot.put("archive_schema_ready",constraint!=null && constraint.contains("'DELETED'"));
        String scope=java.util.stream.Stream.concat(snapshot.get("legacy_listings").findValuesAsText("id").stream(),
                dataset.listingIds().stream().map(UUID::toString)).map(value->"'"+UUID.fromString(value)+"'::uuid")
                .collect(java.util.stream.Collectors.joining(","));
        snapshot.set("preserved_listings",read("select * from listings where id not in ("+scope+")"));
        snapshot.set("foreign_keys",read("select c.conname,c.conrelid::regclass::text as child_table,"
                +"c.confrelid::regclass::text as parent_table,c.convalidated,pg_get_constraintdef(c.oid) as definition "
                +"from pg_constraint c where c.contype='f' and c.connamespace='public'::regnamespace"));
        snapshot.put("status_foreign_key_references",jdbc.queryForObject("select count(*) from pg_constraint c "
                +"where c.contype='f' and c.confrelid='listings'::regclass and exists "
                +"(select 1 from pg_attribute a where a.attrelid=c.confrelid and a.attnum=any(c.confkey) and a.attname='status')",Integer.class));
        List<String> blockers=inspect(snapshot);
        return new Preview(DemoSeed.digest(snapshot.toString()),snapshot,List.copyOf(blockers));
    }

    public void apply(String approvedToken,Path backupManifest) {
        if (!env.acceptsProfiles(Profiles.of("demo & !prod & !production"))
                || !env.getProperty("foodbridge.presentation.maintenance-enabled",Boolean.class,false))
            throw new IllegalStateException("Manual maintenance requires explicit enable flag, demo profile and neither prod nor production.");
        if (approvedToken==null || !approvedToken.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Explicit reviewed preview token is required.");
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A real Spring-managed transaction is required.");
        String host=env.getRequiredProperty("foodbridge.presentation.expected-host");
        String backupHash=backupVerifier.verify(backupManifest,approvedToken,host);
        jdbc.execute("set local lock_timeout='5s'");
        jdbc.execute("lock table users,listings,requests,handovers,activity in share row exclusive mode");
        Preview current=preview();
        if (!current.blockers().isEmpty()) throw new IllegalStateException("Maintenance blocked: "+String.join("; ",current.blockers()));
        boolean installed=true;
        for(JsonNode legacy:current.affectedRecords().get("legacy_listings"))
            installed &= legacy.path("status").asText().equals("DELETED");
        installed &= current.affectedRecords().get("replacement_listings").size()==40;
        // A completed operation is a no-op, even when its original successful token is reused.
        // The same original backup still has to pass integrity/target/token verification above.
        if(installed) return;
        if(!current.approvalToken().equals(approvedToken))
            throw new IllegalStateException("Records/schema/dataset changed since preview. No records changed; review a fresh preview and obtain approval again.");
        Instant epoch=Instant.now();
        journal.prepare(current.affectedRecords(),approvedToken,backupHash,jdbc.queryForObject("select current_user",String.class));
        for(JsonNode legacy:current.affectedRecords().get("legacy_listings")) {
            if(legacy.path("status").asText().equals("DELETED")) continue;
            int changed=jdbc.update("update listings set status='DELETED',updated_at=? where id=? and status=?",
                    Timestamp.from(epoch),DemoSeed.uuid(legacy,"id"),legacy.path("status").asText());
            if(changed!=1) throw new IllegalStateException("Legacy status update conflict; transaction must roll back.");
        }
        // Reuse existing demo users without changing their names, roles, hashes or timestamps.
        for(JsonNode listing:dataset.root().get("listings")) {
            if(DemoSeed.find(current.affectedRecords().get("replacement_listings"),listing.path("id").asText())!=null) continue;
            jdbc.update("""
                insert into listings(id,donor_id,title,description,category,quantity,unit,image_url,pickup_area,
                    collection_deadline,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,'AVAILABLE',?,?)
                """,DemoSeed.uuid(listing,"id"),DemoSeed.uuid(listing,"donor_id"),listing.path("title").asText(),
                    listing.path("description").asText(),listing.path("category").asText(),listing.path("quantity").decimalValue(),
                    listing.path("unit").asText(),listing.get("image_url").isNull()?null:listing.path("image_url").asText(),
                    listing.path("pickup_area").asText(),Timestamp.from(DemoSeedDataset.collectionDeadline(listing,epoch)),Timestamp.from(epoch),Timestamp.from(epoch));
        }
        Preview result=preview();
        if(!result.blockers().isEmpty() || result.affectedRecords().get("replacement_listings").size()!=40)
            throw new IllegalStateException("Postcondition failed; transaction must roll back: "+result.blockers());
        for(JsonNode legacy:result.affectedRecords().get("legacy_listings"))
            if(!legacy.path("status").asText().equals("DELETED")) throw new IllegalStateException("Legacy archive incomplete; roll back.");
        for(String section:List.of("requests","handovers","users","preserved_listings"))
            if(!current.affectedRecords().get(section).equals(result.affectedRecords().get(section)))
                throw new IllegalStateException("Preserved records changed in "+section+"; transaction must roll back.");
        for(JsonNode event:current.affectedRecords().get("activity"))
            if(!event.equals(DemoSeed.find(result.affectedRecords().get("activity"),event.path("id").asText())))
                throw new IllegalStateException("Existing activity changed; transaction must roll back.");
    }

    private List<String> inspect(ObjectNode snapshot) {
        var blockers=new ArrayList<String>();
        if(!snapshot.path("archive_schema_ready").asBoolean())
            blockers.add("Existing V4 DELETED status constraint is missing. No migration or schema bypass is run by this command.");
        if(snapshot.path("expected_host").asText().isBlank()) blockers.add("Expected database host must be explicit.");
        if(snapshot.path("status_foreign_key_references").asInt()!=0)
            blockers.add("Unexpected foreign key references listing status; review possible dependent updates before archiving.");
        if(snapshot.get("legacy_listings").size()!=12) blockers.add("All 12 exact original listing IDs must exist; no automatic repair.");
        JsonNode admin=DemoSeed.find(snapshot.get("users"),DemoSeed.id("admin").toString());
        if(admin==null || !admin.path("email").asText().equals("admin@demo.foodbridge.invalid")
                || !admin.path("role").asText().equals("ADMIN")) blockers.add("Demo admin audit identity conflict.");
        for(JsonNode donor:dataset.root().get("donors")) {
            JsonNode existing=DemoSeed.find(snapshot.get("users"),donor.path("id").asText());
            if(existing==null || !same(existing,donor,"id","email","role")) blockers.add("Existing donor identity conflict: "+donor.path("id").asText());
        }
        for(JsonNode legacy:snapshot.get("legacy_listings")) {
            int number=java.util.stream.IntStream.rangeClosed(1,12).filter(i->DemoSeed.id("listing"+i).toString().equals(legacy.path("id").asText())).findFirst().orElseThrow();
            String expectedTitle="SIMULATED "+(number==10?"Community lunch surplus with assorted vegetables and rice for multiple recipient organizations collecting at the designated afternoon pickup point ":"Meal donation ")+number;
            if(!legacy.path("title").asText().equals(expectedTitle)
                    || !legacy.path("donor_id").asText().equals(DemoSeed.id(number%2==0?"donor2":"donor1").toString())
                    || !legacy.path("description").asText().equals("Fictional demonstration listing; no real food is offered.")
                    || !legacy.path("category").asText().equals("PREPARED_MEALS")
                    || legacy.path("quantity").decimalValue().compareTo(java.math.BigDecimal.valueOf(20))!=0
                    || !legacy.path("unit").asText().equals("PORTIONS")
                    || !legacy.path("pickup_area").asText().equals("SIMULATED Lagos pickup")
                    || !Objects.equals(legacy.get("image_url").isNull()?null:legacy.path("image_url").asText(),number==9?null:"https://example.invalid/simulated-food.jpg"))
                blockers.add("Legacy identity/content changed: "+legacy.path("id").asText());
            if(legacy.path("status").asText().equals("DELETED")) {
                if(!journal.validates(legacy,snapshot)) blockers.add("Deleted legacy row lacks valid external before-image audit: "+legacy.path("id").asText());
            }
        }
        for(JsonNode existing:snapshot.get("replacement_listings")) {
            JsonNode expected=DemoSeed.find(dataset.root().get("listings"),existing.path("id").asText());
            if(!same(existing,expected,"donor_id","title","description","category","unit","image_url","pickup_area")
                    || existing.path("quantity").decimalValue().compareTo(expected.path("quantity").decimalValue())!=0)
                blockers.add("Replacement content conflict: "+existing.path("id").asText());
            if(existing.path("collection_deadline").isTextual()
                    && Instant.parse(existing.path("collection_deadline").asText()).isBefore(DemoSeedDataset.PRESENTATION_DEADLINE_FLOOR))
                blockers.add("Existing replacement deadline precedes presentation floor; no automatic retiming: "+existing.path("id").asText());
        }
        int count=snapshot.get("replacement_listings").size();
        if(count!=0 && count!=40) blockers.add("Partial replacement dataset; automatic resurrection is prohibited.");
        int deleted=0;
        for(JsonNode legacy:snapshot.get("legacy_listings")) if(legacy.path("status").asText().equals("DELETED")) deleted++;
        if(deleted>0 && (deleted!=12 || count!=40)) blockers.add("Partial/inconsistent prior archive operation; no automatic repair or resurrection.");
        return blockers;
    }
    public static UUID archiveId(UUID listing) {return DemoSeed.id("presentation-archive-v1-"+listing);}
    private JsonNode read(String sql) {
        try {
            return JSON.readTree(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from ("+sql+") t",String.class));
        } catch(java.io.IOException e) {throw new IllegalStateException("Cannot read maintenance inventory",e);}
    }
    private static boolean same(JsonNode a,JsonNode b,String... fields) {
        for(String field:fields) if(!Objects.equals(a.get(field),b.get(field))) return false;
        return true;
    }
}

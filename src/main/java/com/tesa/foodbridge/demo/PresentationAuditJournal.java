package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Immutable external before-image audit for V1-V4; no new DB activity type or DDL is required. */
final class PresentationAuditJournal {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path path;
    PresentationAuditJournal(Path directory,String host) {
        path=directory.toAbsolutePath().normalize().resolve("presentation-v4-"+DemoSeed.digest(host+"/neondb").substring(0,16)+".json");
    }
    Path path() {return path;}
    JsonNode read() {
        if(!Files.exists(path)) return null;
        try {return JSON.readTree(path.toFile());}
        catch(Exception e) {throw new IllegalStateException("Cannot read external before-image audit; stop for review.",e);}
    }
    void prepare(JsonNode snapshot,String token,String backupHash,String databaseRole) {
        ObjectNode audit=JSON.createObjectNode();
        audit.put("operation",PresentationSeedMaintenance.OPERATION);
        audit.put("state","PREPARED_NOT_PROOF_OF_COMMIT");
        audit.put("approved_preview",token);audit.put("backup_sha256",backupHash);
        audit.put("database_role",databaseRole);audit.put("created_at_utc",Instant.now().toString());
        audit.set("before",snapshot.deepCopy());
        JsonNode existing=read();
        if(existing!=null) {
            if(!existing.path("approved_preview").asText().equals(token)
                    || !existing.path("backup_sha256").asText().equals(backupHash)
                    || !existing.path("before").equals(snapshot))
                throw new IllegalStateException("A different external audit exists. Preserve it and review; it will not be overwritten.");
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(audit);
            try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
            }
            if(!JSON.readTree(path.toFile()).equals(audit)) throw new IllegalStateException("Audit read-back failed");
        } catch(Exception e) {throw new IllegalStateException("Cannot durably preserve previous statuses; no DB updates permitted.",e);}
    }
    boolean validates(JsonNode legacy,JsonNode current) {
        JsonNode audit=read();if(audit==null) return false;
        if(!audit.path("operation").asText().equals(PresentationSeedMaintenance.OPERATION)
                || !audit.path("approved_preview").asText().matches("[0-9a-f]{64}")
                || !audit.path("backup_sha256").asText().matches("[0-9a-f]{64}")
                || !audit.path("before").path("dataset_sha256").equals(current.path("dataset_sha256"))
                || !audit.path("before").path("expected_host").equals(current.path("expected_host"))) return false;
        JsonNode before=DemoSeed.find(audit.path("before").path("legacy_listings"),legacy.path("id").asText());
        if(before==null || !Set.of("AVAILABLE","RESERVED","COLLECTED","REVERSED","EXPIRED").contains(before.path("status").asText())) return false;
        for(String key:List.of("id","donor_id","title","description","category","quantity","unit","image_url","pickup_area","collection_deadline","created_at"))
            if(!Objects.equals(before.get(key),legacy.get(key))) return false;
        return true;
    }
}

package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Verifies an actual archive again at execution time; never restores or connects to a database. */
public class BackupVerifier {
    public String verify(Path manifestPath,String previewToken,String expectedHost) {
        if(manifestPath==null) throw new IllegalStateException("Verified backup manifest is required.");
        try {
            JsonNode manifest=new ObjectMapper().readTree(manifestPath.toFile());
            String direct=expectedHost.replace("-pooler.",".");
            if(!manifest.path("preview_token").asText().equals(previewToken)
                    || !manifest.path("preview_checked_before_and_after").asBoolean()
                    || !manifest.path("direct_host").asText().equals(direct)
                    || !manifest.path("database").asText().equals("neondb"))
                throw new IllegalStateException("Backup is not bound to this reviewed preview and database target.");
            Path archive=Path.of(manifest.path("archive").asText()).toAbsolutePath();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(archive)) {
                byte[] buffer=new byte[65536]; int size;
                while((size=input.read(buffer))!=-1) digest.update(buffer,0,size);
            }
            String hash=HexFormat.of().formatHex(digest.digest());
            if(!hash.equals(manifest.path("sha256").asText()) || !manifest.path("archive_readable").asBoolean())
                throw new IllegalStateException("Backup integrity/verification failed.");
            String tool=System.getProperty("os.name").startsWith("Windows")
                    ? "C:\\Program Files\\PostgreSQL\\18\\bin\\pg_restore.exe" : "pg_restore";
            String toc=run(List.of(tool,"--list",archive.toString()));
            for(String table:List.of("users","listings","requests","handovers","activity","flyway_schema_history"))
                if(!toc.contains("TABLE DATA public "+table+" ")) throw new IllegalStateException("Backup lacks table data for "+table);
            String nullDevice=System.getProperty("os.name").startsWith("Windows")?"NUL":"/dev/null";
            run(List.of(tool,"--no-owner","--no-acl","--file="+nullDevice,archive.toString()));
            return hash;
        } catch(IllegalStateException e) {throw e;}
        catch(Exception e) {throw new IllegalStateException("Cannot verify backup; no database changes allowed.",e);}
    }
    private String run(List<String> command) throws Exception {
        Path output=Files.createTempFile("foodbridge-backup-check-",".txt");
        try {
            Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if(!process.waitFor(120,TimeUnit.SECONDS)) {process.destroyForcibly();throw new IllegalStateException("Backup verification timed out.");}
            if(process.exitValue()!=0) throw new IllegalStateException("pg_restore archive verification failed. No restore was attempted.");
            return Files.readString(output);
        } finally {Files.deleteIfExists(output);}
    }
}

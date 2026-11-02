package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresentationSeedSafetyTest {
    @TempDir Path temp;
    @Test void productionAndDisabledMaintenanceRejectBeforeAnyDatabaseOrBackupAccess() {
        for(String profile:List.of("prod","production","demo")) {
            var jdbc=mock(JdbcTemplate.class);var backup=mock(BackupVerifier.class);
            var env=new MockEnvironment().withProperty("foodbridge.presentation.maintenance-enabled",profile.equals("demo")?"false":"true");
            env.setActiveProfiles("demo",profile);
            var command=new PresentationSeedMaintenance(jdbc,env,backup);
            assertThrows(IllegalStateException.class,()->command.apply("a".repeat(64),temp.resolve("backup.json")));
            verifyNoInteractions(jdbc,backup);
        }
    }
    @Test void approvalAndTransactionAreMandatoryBeforeBackupOrSql() {
        var jdbc=mock(JdbcTemplate.class);var backup=mock(BackupVerifier.class);
        var env=new MockEnvironment().withProperty("foodbridge.presentation.maintenance-enabled","true");env.setActiveProfiles("demo");
        var command=new PresentationSeedMaintenance(jdbc,env,backup);
        assertThrows(IllegalStateException.class,()->command.apply(null,null));
        assertThrows(IllegalStateException.class,()->command.apply("a".repeat(64),null));
        verifyNoInteractions(jdbc,backup);
    }
    @Test void verifierRejectsMissingOrWrongTargetManifest() throws Exception {
        var verifier=new BackupVerifier();
        assertThrows(IllegalStateException.class,()->verifier.verify(null,"a".repeat(64),"expected.example"));
        Path manifest=temp.resolve("backup.json");
        Files.writeString(manifest,new ObjectMapper().writeValueAsString(Map.of("preview_token","a".repeat(64),"database","other","direct_host","wrong.example")));
        assertThrows(IllegalStateException.class,()->verifier.verify(manifest,"a".repeat(64),"expected.example"));
    }
    @Test void verifierRejectsTamperedArchiveBeforeInvokingNativeTools() throws Exception {
        Path archive=temp.resolve("backup.dump"),manifest=temp.resolve("backup.json");
        Files.writeString(archive,"not a valid backup");
        Files.writeString(manifest,new ObjectMapper().writeValueAsString(Map.of("preview_token","a".repeat(64),"database","neondb",
                "direct_host","expected.example","archive",archive.toString(),"sha256","b".repeat(64),"archive_readable",true,"preview_checked_before_and_after",true)));
        assertThrows(IllegalStateException.class,()->new BackupVerifier().verify(manifest,"a".repeat(64),"expected.example"));
    }
}

package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual CLI dispatch without a Boot context or another JVM/database. */
@Testcontainers
class PresentationLifecycleCommandPostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName(PresentationLifecycleScenarios.DATABASE).withStartupTimeout(Duration.ofMinutes(3));
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();

    @Test void documentedCliProvisionsFreshDatabaseAndRerunsWithoutMutation() throws Exception {
        run("migrate",null);
        JsonNode plan=run("preview",null);
        assertTrue(plan.path("blockers").isEmpty(),plan.toString());
        String token=plan.path("approvalToken").asText();
        String alternative=alternateHost(DB.getJdbcUrl().split("\\?")[0]);
        assertThrows(IllegalStateException.class,()->run("apply",token,alternative));
        assertEquals(0,run("preview",null).path("affectedRecords").path("current_records").path("listings").size());
        JsonNode installed=run("apply",token);
        assertEquals(40,installed.path("affectedRecords").path("current_records").path("listings").size());
        assertEquals(installed,run("apply",token));
        assertEquals(installed,run("preview",null));
    }
    JsonNode run(String mode,String token) throws Exception {
        return run(mode,token,DB.getJdbcUrl().split("\\?")[0]);
    }

    @Test void previewTokenIsBoundToTheReviewedEndpoint() throws Exception {
        run("migrate",null);
        String original=DB.getJdbcUrl().split("\\?")[0];
        String alternative=alternateHost(original);
        var first=run("preview",null,original);var second=run("preview",null,alternative);
        assertEquals(DB.getMappedPort(5432).intValue(),first.path("affectedRecords").path("target_identity").path("port").asInt());
        assertEquals(PresentationLifecycleScenarios.DATABASE,first.path("affectedRecords").path("target_identity").path("database").asText());
        assertNotEquals(first.path("approvalToken"),second.path("approvalToken"));
    }

    String alternateHost(String url) {
        return url.contains("//localhost:")?url.replace("//localhost:","//127.0.0.1:")
                :url.replace("//127.0.0.1:","//localhost:");
    }

    JsonNode run(String mode,String token,String url) throws Exception {
        Path output=temp.resolve("result-"+UUID.randomUUID()+".json");
        var args=new ArrayList<>(List.of("--enable=true","--scenarios=lifecycle-v1","--disposable=true","--mode="+mode,"--output="+output));
        if(token!=null)args.add("--approved-preview="+token);
        PresentationSeedCommand.run(args.toArray(String[]::new),Map.of(
                "FOODBRIDGE_DISPOSABLE_URL",url,
                "FOODBRIDGE_DISPOSABLE_USER",DB.getUsername(),"FOODBRIDGE_DISPOSABLE_PASSWORD",DB.getPassword()));
        return mode.equals("migrate")?json.createObjectNode():json.readTree(output.toFile());
    }
}

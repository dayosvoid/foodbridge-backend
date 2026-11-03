package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Explicit command-line entry point only. Never registered or executed by Spring Boot startup. */
public final class PresentationSeedCommand {
    public static void main(String[] args) throws Exception {
        run(args,System.getenv());
    }

    /** Same command dispatch with an explicit environment map for isolated provisioning tests. */
    static void run(String[] args, Map<String,String> commandEnvironment) throws Exception {
        Map<String,String> options=new HashMap<>();
        for(String arg:args) {
            if(!arg.startsWith("--")) throw new IllegalArgumentException("Named arguments required.");
            String[] pair=arg.substring(2).split("=",2);
            if(options.put(pair[0],pair.length==2?pair[1]:"true")!=null) throw new IllegalArgumentException("Duplicate argument");
        }
        if(!"true".equals(options.get("enable"))) throw new IllegalStateException("Explicit --enable is required.");
        if (options.containsKey("scenarios")) {
            disposableLifecycle(options,commandEnvironment);
            return; // Never load .env or use the shared maintenance target for lifecycle provisioning.
        }
        String mode=options.getOrDefault("mode","preview");
        if(!Set.of("preview","apply").contains(mode)) throw new IllegalArgumentException("mode must be preview or apply");
        String host=Objects.requireNonNull(options.get("expected-host"),"--expected-host is required");
        var credentials=new Properties();
        try(var reader=Files.newBufferedReader(Path.of(".env"))){credentials.load(reader);}
        String url=credentials.getProperty("DB_URL");
        URI target=URI.create(url.replaceFirst("^jdbc:",""));
        if(target.getUserInfo()!=null || (target.getQuery()!=null && target.getQuery().matches("(?i).*(^|&)(password|user)=.*")))
            throw new IllegalStateException("Keep credentials in separate .env fields; do not embed them in the connection URL.");
        if(!target.getHost().equals(host) || !target.getPath().equals("/neondb"))
            throw new IllegalStateException("Configured endpoint differs from reviewed target.");
        var environment=new StandardEnvironment();
        if(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod","production")))
            throw new IllegalStateException("Production profiles prohibit this maintenance command.");
        // Local maintenance context only; never changes the deployment's environment.
        String profile=options.getOrDefault("profile","demo");
        if(!profile.equals("demo")) throw new IllegalStateException("Production execution is prohibited; local manual profile must be demo.");
        environment.setActiveProfiles("demo");
        var properties=new HashMap<String,Object>();
        properties.put("foodbridge.presentation.maintenance-enabled",true);
        properties.put("foodbridge.presentation.expected-host",host);
        properties.put("foodbridge.presentation.audit-directory",options.getOrDefault("audit-directory","backups/audits"));
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("manual-maintenance",properties));
        var ds=new DriverManagerDataSource();
        ds.setUrl(url); ds.setUsername(credentials.getProperty("DB_USERNAME")); ds.setPassword(credentials.getProperty("DB_PASSWORD"));
        var connectionProperties=new Properties();
        connectionProperties.setProperty("connectTimeout","15"); connectionProperties.setProperty("socketTimeout","60");
        connectionProperties.setProperty("readOnlyMode","always");
        if(mode.equals("preview")) connectionProperties.setProperty("readOnly","true");
        ds.setConnectionProperties(connectionProperties);
        var manager=new DataSourceTransactionManager(ds); manager.setEnforceReadOnly(true);
        var transaction=new TransactionTemplate(manager);
        transaction.setReadOnly(mode.equals("preview"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var maintenance=new PresentationSeedMaintenance(new JdbcTemplate(ds),environment);
        Object result=transaction.execute(status->{
            if(mode.equals("apply")) maintenance.apply(options.get("approved-preview"),
                    options.containsKey("backup-manifest")?Path.of(options.get("backup-manifest")):null);
            return maintenance.preview();
        });
        String output=new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result);
        if(mode.equals("apply")) {
            var receipt=new ObjectMapper().createObjectNode();
            receipt.put("state","COMMITTED_AND_VERIFIED");receipt.put("committed_at_utc",java.time.Instant.now().toString());
            receipt.put("approved_preview",options.get("approved-preview"));
            receipt.set("post_commit_preview",new ObjectMapper().valueToTree(result));
            Path journal=Path.of(((PresentationSeedMaintenance.Preview)result).affectedRecords().path("audit_journal").asText());
            Path receiptPath=Path.of(journal.toString()+".commit.json");
            if(!Files.exists(receiptPath)) {
                try(var channel=java.nio.channels.FileChannel.open(receiptPath,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    var bytes=java.nio.ByteBuffer.wrap(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
                    while(bytes.hasRemaining()) channel.write(bytes);channel.force(true);
                } catch(Exception e) {throw new IllegalStateException("Database committed and verified, but commit receipt could not be saved. Preserve the before-image journal and run read-only verification.",e);}
            }
        }
        if(options.containsKey("output")) Files.writeString(Path.of(options.get("output")),output);
        else System.out.println(output);
    }

    private static void disposableLifecycle(Map<String,String> options,Map<String,String> commandEnvironment) throws Exception {
        if (!"lifecycle-v1".equals(options.get("scenarios")) || !"true".equals(options.get("disposable")))
            throw new IllegalStateException("Lifecycle scenarios require --scenarios=lifecycle-v1 --disposable=true.");
        String mode = options.getOrDefault("mode","preview");
        if (!Set.of("migrate","preview","apply").contains(mode)) throw new IllegalArgumentException("Unsupported lifecycle mode");
        var env = new StandardEnvironment();
        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod","production"))
                || !"demo".equals(options.getOrDefault("profile","demo")))
            throw new IllegalStateException("Production profiles prohibit lifecycle provisioning.");
        env.setActiveProfiles("demo");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("disposable-maintenance",Map.of(
                "foodbridge.presentation.maintenance-enabled",true,
                "foodbridge.presentation.lifecycle-disposable-enabled",true)));
        String url = Objects.requireNonNull(commandEnvironment.get("FOODBRIDGE_DISPOSABLE_URL"),"FOODBRIDGE_DISPOSABLE_URL required");
        PresentationLifecycleScenarios.validateTarget(url); // Before opening any connection.
        var ds = new DriverManagerDataSource(url,
                Objects.requireNonNull(commandEnvironment.get("FOODBRIDGE_DISPOSABLE_USER"),"Disposable user required"),
                Objects.requireNonNull(commandEnvironment.get("FOODBRIDGE_DISPOSABLE_PASSWORD"),"Disposable password required"));
        if (mode.equals("migrate")) {
            org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
            return; // Explicit schema-only mode, never an implicit preview side effect.
        }
        // Only JDBC domain services are wired; no Boot startup, .env import, web/security
        // context, automatic Flyway, scheduler or extra seed runner is started.
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.setEnvironment(env);
            context.registerBean(javax.sql.DataSource.class,() -> ds);
            context.registerBean(JdbcTemplate.class,() -> new JdbcTemplate(ds));
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,() -> {
                var manager = new DataSourceTransactionManager(ds);
                manager.setEnforceReadOnly(true);
                return manager;
            });
            context.register(DisposableDomainOperations.class);
            context.refresh();
            var tx = new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.setReadOnly(mode.equals("preview"));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var maintenance = new PresentationSeedMaintenance(context.getBean(JdbcTemplate.class),env);
            var operations = new PresentationSeedMaintenance.LifecycleOperations(
                    context.getBean(com.tesa.foodbridge.listing.api.ListingOperations.class),
                    context.getBean(com.tesa.foodbridge.handover.api.HandoverOperations.class),
                    context.getBean(com.tesa.foodbridge.expiration.api.ExpirationOperations.class),
                    context.getBean(com.tesa.foodbridge.distribution.api.DailyDistributionOperations.class));
            var result = tx.execute(status -> {
                if (mode.equals("apply")) maintenance.applyLifecycle(options.get("approved-preview"),operations);
                return maintenance.lifecyclePreview();
            });
            String output = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result);
            if(options.containsKey("output")) Files.writeString(Path.of(options.get("output")),output);
            else System.out.println(output);
        }
    }

    // Explicitly registered lite configuration, deliberately not a component:
    // Boot's normal package scan must never discover this CLI-only context.
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @org.springframework.context.annotation.ComponentScan(basePackages={
            "com.tesa.foodbridge.listing.service", "com.tesa.foodbridge.listing.repository",
            "com.tesa.foodbridge.user.service", "com.tesa.foodbridge.activity.service",
            "com.tesa.foodbridge.handover.service", "com.tesa.foodbridge.expiration.service",
            "com.tesa.foodbridge.distribution.service"})
    static class DisposableDomainOperations {}
}

package com.tesa.foodbridge.demo;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Static safeguards; PostgreSQL syntax/execution is separately tested in PostgresRulesTest. */
class DemoSeedVerificationSqlTest {
    @Test void verificationScriptOnlyReadsAndUsesExactSeederIds() throws Exception {
        String sql=Files.readString(Path.of("docs/sql/verify-demo-seed.sql"));
        String statements=sql.replaceAll("(?m)--[^\\r\\n]*","");
        assertTrue(statements.stripLeading().startsWith("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;"));
        assertTrue(statements.stripTrailing().endsWith("ROLLBACK;"));
        for(String forbidden:List.of("INSERT","UPDATE","DELETE","TRUNCATE","ALTER","DROP","CREATE","CALL","DO","COPY"))
            assertFalse(Pattern.compile("(?i)\\b"+forbidden+"\\b").matcher(
                statements.replaceAll("'(?:[^']|'')*'","''")).find(),"Unexpected write operation: "+forbidden);

        var groups=new LinkedHashMap<String,Set<String>>();
        var counts=new LinkedHashMap<String,Integer>();
        var matcher=Pattern.compile("\\('([^']+)', '([^']+)', '([0-9a-f-]{36})'::uuid\\)").matcher(sql);
        while(matcher.find()) {
            String kind=matcher.group(1),key=matcher.group(2);
            assertEquals(DemoSeed.id(key).toString(),matcher.group(3),"Verification ID must match Java's seeder ID: "+key);
            groups.computeIfAbsent(kind,ignored->new HashSet<>()).add(key);
            counts.merge(kind,1,Integer::sum);
        }
        assertEquals(Map.of("replacement_listing",40,"legacy_listing",12,"fixture_user",6,"legacy_request",8,"legacy_handover",3),
            groups.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().size())));
        // There are two independent scoped queries; neither may omit or duplicate an identity.
        groups.forEach((kind,keys)->assertEquals(keys.size()*2,counts.get(kind)));
        var expectedNew=new HashSet<String>();
        for(int i=1;i<=40;i++) expectedNew.add(String.format(Locale.ROOT,"replacement-v1-listing-%02d",i));
        assertEquals(expectedNew,groups.get("replacement_listing"));
    }
}

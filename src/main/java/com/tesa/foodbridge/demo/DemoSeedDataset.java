package com.tesa.foodbridge.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Data loader/validator only; DemoSeed remains the single fixture runner. */
record DemoSeedDataset(JsonNode root, String sha256) {
    static final Instant PRESENTATION = Instant.parse("2026-11-03T09:00:00Z");
    static final Instant PRESENTATION_DEADLINE_FLOOR = Instant.parse("2026-11-05T17:00:00Z");

    static Instant collectionDeadline(JsonNode listing, Instant epoch) {
        Instant relative = epoch.plusSeconds(listing.path("collection_deadline").path("offset_seconds").asLong());
        Instant floor = Instant.parse(listing.path("collection_deadline").path("not_before").asText());
        return relative.isAfter(floor) ? relative : floor;
    }
    static DemoSeedDataset load() {
        try (var stream = new ClassPathResource("demo/replacement-listings-v1.json").getInputStream()) {
            String source = new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            var dataset = new DemoSeedDataset(new ObjectMapper().readTree(source),DemoSeed.digest(source));
            dataset.validate();
            return dataset;
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load approved demo dataset",e); }
    }
    List<UUID> listingIds() {
        var result = new ArrayList<UUID>();
        root.get("listings").forEach(row -> result.add(DemoSeed.uuid(row,"id")));
        return result;
    }
    void validate() {
        require(root.path("dataset_key").asText().equals("foodbridge-demo-replacement-v1"),"dataset identity");
        require(root.path("donors").size()==2 && root.path("listings").size()==40,"record counts");
        var donors = new HashSet<String>();
        for (JsonNode donor : root.get("donors")) {
            String key = donor.path("seed_key").asText();
            require(Set.of("donor1","donor2").contains(key) && donors.add(donor.path("id").asText()),"donor key/uniqueness");
            require(DemoSeed.id(key).equals(DemoSeed.uuid(donor,"id")),"donor ID");
            require(donor.path("email").asText().equals(key+"@demo.foodbridge.invalid"),"demo email");
            require(donor.path("role").asText().equals("DONOR") && nonblank(donor,"display_name",200),"donor role/name");
        }
        var ids = new HashSet<UUID>(); var images = new HashSet<String>(); int number=0;
        for (JsonNode listing : root.get("listings")) {
            String key="replacement-v1-listing-"+String.format(Locale.ROOT,"%02d",++number);
            require(listing.path("seed_key").asText().equals(key),"listing key/order");
            require(DemoSeed.id(key).equals(DemoSeed.uuid(listing,"id")) && ids.add(DemoSeed.uuid(listing,"id")),"listing ID/uniqueness");
            require(donors.contains(listing.path("donor_id").asText()),"donor foreign key");
            require(nonblank(listing,"title",300) && nonblank(listing,"description",Integer.MAX_VALUE) && nonblank(listing,"pickup_area",200),"required listing text");
            require(Set.of("PREPARED_MEALS","PRODUCE","BAKERY","DAIRY","PANTRY","OTHER").contains(listing.path("category").asText()),"category");
            require(Set.of("PORTIONS","KG","LITRES","ITEMS","BOXES").contains(listing.path("unit").asText()),"unit");
            require(listing.path("quantity").isNumber(),"numeric quantity");
            BigDecimal q=listing.path("quantity").decimalValue();
            require(q.signum()>0 && q.scale()<=2 && q.compareTo(new BigDecimal("10000000000"))<0,"numeric(12,2)");
            require(listing.path("status").asText().equals("AVAILABLE"),"initial state");
            require(listing.path("collection_deadline").path("not_before").asText()
                    .equals(PRESENTATION_DEADLINE_FLOOR.toString()),"presentation deadline floor");
            for (String field : List.of("collection_deadline","created_at","updated_at")) {
                JsonNode recipe=listing.path(field);
                require(recipe.path("anchor").asText().equals("demo_epoch") && recipe.path("offset_seconds").isIntegralNumber(),"timestamp recipe");
                long seconds=recipe.path("offset_seconds").asLong();
                require(field.equals("collection_deadline") ? seconds>0 && seconds<=604800 : seconds==0,"timestamp offset");
            }
            require(listing.has("image_url"),"explicit nullable image field");
            if (!listing.get("image_url").isNull()) {
                String url=listing.path("image_url").asText(); URI uri=URI.create(url);
                require("https".equals(uri.getScheme()) && "res.cloudinary.com".equals(uri.getHost())
                        && uri.getUserInfo()==null && images.add(url),"unique Cloudinary HTTPS URL");
            }
        }
        require(images.size()==36,"36 pictured / 4 unpictured");
    }
    private static boolean nonblank(JsonNode node,String field,int max) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank() && node.path(field).asText().length()<=max;
    }
    private static void require(boolean condition,String issue) {
        if (!condition) throw new IllegalStateException("Invalid packaged demo dataset: "+issue);
    }
}

package com.tesa.foodbridge.activity.service;
import com.tesa.foodbridge.activity.api.ActivityRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
@Service
class DatabaseActivityRecorder implements ActivityRecorder {
    private final JdbcTemplate jdbc;
    DatabaseActivityRecorder(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(UUID actor, UUID listing, String type) {
        jdbc.update("insert into activity(id,actor_id,listing_id,type,details) values (?,?,?,?,?)",
            UUID.randomUUID(),actor,listing,type,"{}");
    }
}

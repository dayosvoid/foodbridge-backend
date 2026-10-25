package com.tesa.foodbridge.handover.service;
import com.tesa.foodbridge.handover.api.HandoverOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
@Service
class DatabaseHandoverOperations implements HandoverOperations {
    private final JdbcTemplate jdbc;
    DatabaseHandoverOperations(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void createForApprovedRequest(UUID requestId) {
        jdbc.update("insert into handovers(id,request_id,status) values (?,?,'AWAITING_COLLECTION')", UUID.randomUUID(),requestId);
    }
}

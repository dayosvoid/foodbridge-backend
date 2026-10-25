package com.tesa.foodbridge.user.service;

import com.tesa.foodbridge.shared.Actor;
import com.tesa.foodbridge.shared.RuleViolation;
import com.tesa.foodbridge.user.api.UserDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
class DatabaseUserDirectory implements UserDirectory {
    private final JdbcTemplate jdbc;
    DatabaseUserDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void requireRole(UUID id, Actor.Role role) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from users where id=? and role=?)", Boolean.class, id, role.name())))
            throw new RuleViolation("FORBIDDEN");
    }
}

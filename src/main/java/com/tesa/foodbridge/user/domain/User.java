package com.tesa.foodbridge.user.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="users")
public class User {
    @Id private UUID id;
    @Column(nullable=false,length=254) private String email;
    @Column(name="password_hash",nullable=false,columnDefinition="text") private String passwordHash;
    @Column(name="display_name",nullable=false,length=200) private String displayName;
    @Column(nullable=false,length=20) private String role;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected User() {}
}

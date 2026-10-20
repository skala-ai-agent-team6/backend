package com.skala.backend.repair;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="app_user")
public class AppUser {
    @Id Long id;
    String email;
    String name;
    boolean active;
    @Column(name="password_hash") String passwordHash;
    String role;
    @Column(name="center_id") Long centerId;
    protected AppUser() {}
}

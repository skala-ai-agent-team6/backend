package com.skala.backend.repair;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface UserRepository extends JpaRepository<AppUser,Long> { Optional<AppUser> findByEmail(String email); }

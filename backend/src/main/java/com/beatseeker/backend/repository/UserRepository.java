package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByIidxId(String iidxId);

    Optional<User> findByEmail(String email);

    Optional<User> findByPasswordResetToken(String token);

    @Query("SELECT u FROM User u WHERE u.iidxId = :query OR u.iidxId = :variant OR u.displayName = :query")
    List<User> searchUsers(@org.springframework.data.repository.query.Param("query") String query,
            @org.springframework.data.repository.query.Param("variant") String variant);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.pushSubscription = NULL")
    void clearAllPushSubscriptions();
}

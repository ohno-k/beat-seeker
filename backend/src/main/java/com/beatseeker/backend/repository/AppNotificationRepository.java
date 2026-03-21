package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AppNotificationRepository extends JpaRepository<AppNotification, Long> {

    List<AppNotification> findByRecipientOrderByCreatedAtDesc(User recipient);

    long countByRecipientAndReadFalse(User recipient);

    @Modifying
    @Query("UPDATE AppNotification n SET n.read = true WHERE n.recipient = :recipient")
    void markAllReadByRecipient(@org.springframework.data.repository.query.Param("recipient") User recipient);
}

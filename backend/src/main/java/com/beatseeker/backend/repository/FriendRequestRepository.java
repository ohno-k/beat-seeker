package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.FriendRequest;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    List<FriendRequest> findByReceiverAndStatus(User receiver, String status);

    Optional<FriendRequest> findBySenderAndReceiverAndStatus(User sender, User receiver, String status);
}

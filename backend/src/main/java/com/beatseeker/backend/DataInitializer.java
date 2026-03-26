package com.beatseeker.backend;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@Component
public class DataInitializer implements ApplicationRunner {

    private final EntityManager entityManager;

    public DataInitializer(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        entityManager.createQuery("UPDATE User u SET u.language = 'ja' WHERE u.language IS NULL")
                .executeUpdate();
    }
}

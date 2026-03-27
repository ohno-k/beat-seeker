package com.beatseeker.backend;

import com.beatseeker.backend.service.GameDataService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class DataInitializer implements ApplicationRunner {

    private final EntityManager entityManager;
    private final GameDataService gameDataService;

    public DataInitializer(EntityManager entityManager, GameDataService gameDataService) {
        this.entityManager = entityManager;
        this.gameDataService = gameDataService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        entityManager.createQuery("UPDATE User u SET u.language = 'ja' WHERE u.language IS NULL")
                .executeUpdate();
        entityManager.createQuery("UPDATE User u SET u.showRateTier = true WHERE u.showRateTier IS NULL")
                .executeUpdate();

        // Seed song data and difficulty table from JSON resources if tables are empty
        try {
            seedFromResource("data/song_data.json", "song");
            seedFromResource("data/difficulty_table.json", "difficulty");
        } catch (Exception e) {
            System.err.println("Warning: Could not seed game data from JSON: " + e.getMessage());
        }
    }

    private void seedFromResource(String resourcePath, String type) throws Exception {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                System.out.println("Seed resource not found: " + resourcePath + " (skipping)");
                return;
            }
            try (InputStream is = resource.getInputStream()) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                if ("song".equals(type)) {
                    gameDataService.seedSongData(json);
                } else {
                    gameDataService.seedDifficultyTable(json);
                }
            }
        } catch (Exception e) {
            System.err.println("Could not seed " + type + " data: " + e.getMessage());
        }
    }
}

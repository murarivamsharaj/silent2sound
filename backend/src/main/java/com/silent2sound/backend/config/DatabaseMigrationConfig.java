package com.silent2sound.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hibernate's ddl-auto=update cannot add NOT NULL columns to tables that
 * already contain rows, so new columns are added here first and backfilled
 * with safe defaults. Idempotent: safe to run on every boot.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DatabaseMigrationConfig implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS has_people BOOLEAN");
            jdbcTemplate.update("UPDATE video_jobs SET has_people = FALSE WHERE has_people IS NULL");
            jdbcTemplate.execute("ALTER TABLE video_jobs ALTER COLUMN has_people SET NOT NULL");
            jdbcTemplate.execute("ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS dialogue_transcript TEXT");
            log.info("Schema migration for video_jobs completed.");
        } catch (DataAccessException e) {
            log.warn("Schema migration skipped or partially applied: {}", e.getMostSpecificCause().getMessage());
        }
    }
}

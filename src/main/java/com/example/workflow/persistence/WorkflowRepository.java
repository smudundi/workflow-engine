package com.example.workflow.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowRepository {

    private final JdbcClient jdbc;

    public WorkflowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(String name, String status, String definitionJson) {
        return jdbc.sql("""
                INSERT INTO workflow (name, status, definition)
                VALUES (:name, :status, CAST(:definition AS jsonb))
                RETURNING id
                """)
                .param("name", name)
                .param("status", status)
                .param("definition", definitionJson)
                .query(UUID.class)
                .single();
    }
}

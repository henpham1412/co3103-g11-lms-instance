package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapMigrationH2Test {

    private Connection connection;

    @BeforeEach
    void migrateV2EquivalentSchemaUsingRealV3() throws SQLException {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        connection = DriverManager.getConnection(url, "sa", "");
        // V1/V2 are immutable historical PostgreSQL scripts. V3 must also run on native H2.
        execute("""
                CREATE TABLE lms_user_access (
                    user_id UUID PRIMARY KEY,
                    full_name VARCHAR(150) NOT NULL,
                    email VARCHAR(255) NOT NULL,
                    lms_role VARCHAR(30) NOT NULL,
                    access_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    last_login_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_lms_user_access_email UNIQUE (email)
                )
                """);
        Flyway.configure().dataSource(url, "sa", "").baselineOnMigrate(true).baselineVersion("2")
                .locations("classpath:db/migration").load().migrate();
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        connection.close();
    }

    @Test
    void seedsExactlyOneIncompleteRowAndRejectsOtherKeys() throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT * FROM lms_setup_state")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("id")).isEqualTo("GLOBAL");
            assertThat(result.getBoolean("is_completed")).isFalse();
            assertThat(result.getObject("completed_at")).isNull();
            assertThat(result.getObject("bootstrapped_by")).isNull();
            assertThat(result.next()).isFalse();
        }
        assertThatThrownBy(() -> execute("INSERT INTO lms_setup_state (id) VALUES ('OTHER')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO lms_setup_state (id) VALUES ('GLOBAL')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void permitsDuplicateEmailsAndPreservesCompletionWhenUserIsDeleted() throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertUser(first);
        insertUser(second);
        try (var statement = connection.prepareStatement("""
                UPDATE lms_setup_state SET is_completed = TRUE,
                    completed_at = CURRENT_TIMESTAMP, bootstrapped_by = ? WHERE id = 'GLOBAL'
                """)) {
            statement.setObject(1, first);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("DELETE FROM lms_user_access WHERE user_id = ?")) {
            statement.setObject(1, first);
            statement.executeUpdate();
        }
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT * FROM lms_setup_state WHERE id = 'GLOBAL'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean("is_completed")).isTrue();
            assertThat(result.getObject("completed_at")).isNotNull();
            assertThat(result.getObject("bootstrapped_by")).isNull();
        }
    }

    @Test
    void foreignKeyRejectsUnknownBootstrapIdentity() {
        assertThatThrownBy(() -> {
            try (var statement = connection.prepareStatement("UPDATE lms_setup_state SET bootstrapped_by = ?")) {
                statement.setObject(1, UUID.randomUUID());
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
    }

    private void insertUser(UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO lms_user_access (user_id, full_name, email, lms_role)
                VALUES (?, 'User', 'shared@example.com', 'STUDENT')
                """)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

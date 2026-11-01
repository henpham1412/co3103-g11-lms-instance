package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapMigrationH2Test {

    private Connection connection;
    private String url;

    @BeforeEach
    void createV2EquivalentSchema() throws SQLException {
        url = "jdbc:h2:mem:" + UUID.randomUUID();
        connection = DriverManager.getConnection(url, "sa", "");
        // Native H2 approximation of final V2; V1/V2 themselves use PostgreSQL syntax.
        execute("""
                CREATE TABLE lms_user_access (
                    user_id UUID PRIMARY KEY,
                    full_name VARCHAR(150) NOT NULL,
                    email VARCHAR(255) NOT NULL,
                    lms_role VARCHAR(30) NOT NULL,
                    access_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    last_login_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        connection.close();
    }

    @Test
    void preservesDuplicateEmailProjectionsWhenMigratingV2EquivalentSchema() throws SQLException {
        insertUser(UUID.randomUUID());
        insertUser(UUID.randomUUID());

        migrateV3();

        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM lms_user_access")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void seedsExactlyOneIncompleteRowAndRejectsOtherKeys() throws SQLException {
        migrateV3();
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
        migrateV3();
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
        migrateV3();
        assertThatThrownBy(() -> {
            try (var statement = connection.prepareStatement("UPDATE lms_setup_state SET bootstrapped_by = ?")) {
                statement.setObject(1, UUID.randomUUID());
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "ADMINISTRATOR, ACTIVE, true",
            "ADMINISTRATOR, SUSPENDED, true",
            "STUDENT, ACTIVE, false",
            "STUDENT, SUSPENDED, false",
            "TUTOR, ACTIVE, false",
            "TUTOR, SUSPENDED, false"
    })
    void migrationSealsSetupForAnyExistingAdministrator(String role, String status,
                                                          boolean completed) throws SQLException {
        // Seed two users before V3 to also verify that it still inserts exactly one setup row.
        UUID selected = new UUID(0, 1);
        // Reverse insertion order and tie creation times to exercise the UUID tie-breaker.
        for (int i = 2; i > 0; i--) {
            UUID id = new UUID(0, i);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO lms_user_access (user_id, full_name, email, lms_role, access_status, created_at)
                    VALUES (?, 'Existing User', ?, ?, ?, TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00')
                    """)) {
                statement.setObject(1, id);
                statement.setString(2, id + "@example.com");
                statement.setString(3, role);
                statement.setString(4, status);
                statement.executeUpdate();
            }
        }

        migrateV3();

        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT * FROM lms_setup_state")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("id")).isEqualTo("GLOBAL");
            assertThat(result.getBoolean("is_completed")).isEqualTo(completed);
            if (completed) {
                assertThat(result.getObject("completed_at")).isNotNull();
            } else {
                assertThat(result.getObject("completed_at")).isNull();
            }
            assertThat(result.getObject("bootstrapped_by", UUID.class)).isEqualTo(completed ? selected : null);
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void selectsOldestAdministratorBeforeUuidRegardlessOfStatus() throws SQLException {
        execute("""
                INSERT INTO lms_user_access (user_id, full_name, email, lms_role, access_status, created_at)
                VALUES ('00000000-0000-0000-0000-000000000001', 'New Admin', 'shared@example.com',
                        'ADMINISTRATOR', 'ACTIVE', TIMESTAMP WITH TIME ZONE '2021-01-01 00:00:00+00'),
                       ('00000000-0000-0000-0000-000000000002', 'Old Admin', 'shared@example.com',
                        'ADMINISTRATOR', 'SUSPENDED', TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00')
                """);
        migrateV3();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT * FROM lms_setup_state")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean("is_completed")).isTrue();
            assertThat(result.getObject("bootstrapped_by", UUID.class))
                    .isEqualTo(new UUID(0, 2));
            assertThat(result.next()).isFalse();
        }
    }

    private void migrateV3() {
        Flyway.configure().dataSource(url, "sa", "").baselineOnMigrate(true).baselineVersion("2")
                .locations("classpath:db/migration").load().migrate();
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

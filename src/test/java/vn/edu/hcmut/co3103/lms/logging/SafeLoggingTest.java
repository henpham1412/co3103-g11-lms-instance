package vn.edu.hcmut.co3103.lms.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class SafeLoggingTest {
    @ParameterizedTest
    @ValueSource(strings = {"INFO", "WARN", "ERROR"})
    void rendersTypesAndFramesButNeverOuterCauseOrSuppressedMessages(String level) {
        var cause = new SQLException("Detail: Key (email)=(row@example.test); ROW_NAME_MARKER", "23505");
        var failure = new IllegalStateException("OUTER_NAME_MARKER Bearer OUTER_TOKEN_MARKER", cause);
        failure.addSuppressed(new IllegalArgumentException("SUPPRESSED_NAME_MARKER"));
        // Include a cycle to exercise the complete throwable graph safely.
        cause.addSuppressed(failure);
        var context = new LoggerContext();
        try {
            var logger = context.getLogger("application.error");
            var event = new LoggingEvent(getClass().getName(), logger, Level.valueOf(level),
                    "Request for {} failed: {}", failure, new Object[]{"FORMATTED_NAME_MARKER", "format@example.test"});
            String rendered = render(context, event);
            assertThat(rendered).contains("Application failure", "java.lang.IllegalStateException", "java.sql.SQLException",
                            "Suppressed: java.lang.IllegalArgumentException", "SafeLoggingTest.java")
                    .doesNotContain("OUTER_NAME_MARKER", "OUTER_TOKEN_MARKER", "ROW_NAME_MARKER", "row@example.test",
                            "SUPPRESSED_NAME_MARKER", "FORMATTED_NAME_MARKER", "format@example.test", "Detail: Key");
        } finally {
            context.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "org.hibernate.orm.jdbc.error, 'ERROR: constraint failure; Detail: Failing row contains (NAME_MARKER, pii@example.test)'",
            "org.hibernate.engine.jdbc.spi.SqlExceptionHelper, 'Detail: Key (name)=(NAME_MARKER) already exists'",
            "org.postgresql.jdbc.PgStatement, 'failing query NAME_MARKER'",
            "org.apache.coyote.http11.Http11Processor, 'Invalid target /?q=[NAME_MARKER'",
            "org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver, 'Content-Type text/plain;note=NAME_MARKER'"
    })
    void suppressesUnsafeFormattedMessagesEvenWithoutAThrowable(String loggerName, String message) {
        var context = new LoggerContext();
        try {
            var event = new LoggingEvent(getClass().getName(), context.getLogger(loggerName), Level.WARN,
                    "{}", null, new Object[]{message});
            assertThat(render(context, event)).contains("suppressed").doesNotContain("NAME_MARKER", "pii@example.test",
                    "Failing row contains", "Detail: Key");
        } finally {
            context.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23514", "22021"})
    void retainsSqlStateAndOrdinaryOperationalMessages(String state) {
        var context = new LoggerContext();
        try {
            var event = new LoggingEvent(getClass().getName(), context.getLogger("org.hibernate.orm.jdbc.error"),
                    Level.WARN, "HHH000247: ErrorCode: 0, SQLState: {}", null, new Object[]{state});
            assertThat(render(context, event)).contains("SQLState: " + state);
            var ordinary = new LoggingEvent(getClass().getName(), context.getLogger("application"), Level.INFO,
                    "Application started on port {}", null, new Object[]{8081});
            assertThat(render(context, ordinary)).contains("Application started on port 8081");
        } finally {
            context.stop();
        }
    }

    private String render(LoggerContext context, LoggingEvent event) {
        var layout = new PatternLayout();
        layout.setContext(context);
        layout.getInstanceConverterMap().put("safeMessage", SafeMessageConverter::new);
        layout.getInstanceConverterMap().put("safeThrowable", SafeThrowableConverter::new);
        layout.setPattern("%level %logger - %safeMessage%n%safeThrowable");
        layout.start();
        try {
            // PatternLayout must recognize the safe throwable converter and not append a raw %ex.
            return layout.doLayout(event);
        } finally {
            layout.stop();
        }
    }
}

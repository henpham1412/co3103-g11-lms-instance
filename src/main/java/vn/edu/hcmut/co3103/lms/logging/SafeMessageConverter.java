package vn.edu.hcmut.co3103.lms.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/** Sanitizes at the output boundary, including messages formatted by framework loggers. */
public final class SafeMessageConverter extends ClassicConverter {
    private static final Pattern SQL_STATE = Pattern.compile("\\bSQLState: ([0-9A-Z]{5})\\b");
    private static final Pattern ROW_DETAIL = Pattern.compile("(?is)\\b(?:Detail:|Failing row contains|Key \\()");
    private static final Pattern EMAIL = Pattern.compile("[\\w.!#$%&'*+/=?^`{|}~-]+@[\\w.-]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;\\]\\\"]+");

    @Override
    public String convert(ILoggingEvent event) {
        String logger = event.getLoggerName();
        String message = event.getFormattedMessage();
        if (logger.startsWith("org.hibernate.orm.jdbc") || logger.startsWith("org.hibernate.engine.jdbc")
                || logger.startsWith("org.postgresql") || (message != null && ROW_DETAIL.matcher(message).find())) {
            var state = SQL_STATE.matcher(message == null ? "" : message);
            return "Database diagnostic; details suppressed" + (state.find() ? "; SQLState: " + state.group(1) : "");
        }
        if (logger.startsWith("org.apache.coyote.http11")) {
            return "HTTP parser diagnostic; request details suppressed";
        }
        if (event.getThrowableProxy() != null) {
            // The outer message can repeat every nested exception, including arbitrary names/row values.
            return "Application failure; message details suppressed";
        }
        if (logger.equals("org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver")) {
            return "MVC request rejected; request details suppressed";
        }
        if (message == null) {
            return "";
        }
        return EMAIL.matcher(BEARER.matcher(message).replaceAll("Bearer [REDACTED]"))
                .replaceAll("[REDACTED_EMAIL]");
    }
}

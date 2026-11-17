package vn.edu.hcmut.co3103.lms.logging;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Keeps exception types and code locations, never exception messages or vendor row details. */
public final class SafeThrowableConverter extends ThrowableHandlingConverter {
    @Override
    public String convert(ILoggingEvent event) {
        var output = new StringBuilder();
        render(event.getThrowableProxy(), "", output, Collections.newSetFromMap(new IdentityHashMap<>()));
        return output.toString();
    }

    private void render(IThrowableProxy throwable, String prefix, StringBuilder output, Set<IThrowableProxy> seen) {
        if (throwable == null || !seen.add(throwable)) {
            return;
        }
        output.append(prefix).append(throwable.getClassName()).append(": [message suppressed]\n");
        var frames = throwable.getStackTraceElementProxyArray();
        if (frames != null) {
            for (var frame : frames) {
                output.append("\tat ").append(frame.getStackTraceElement()).append('\n');
            }
        }
        var suppressed = throwable.getSuppressed();
        if (suppressed != null) {
            for (var exception : suppressed) {
                render(exception, "Suppressed: ", output, seen);
            }
        }
        render(throwable.getCause(), "Caused by: ", output, seen);
    }
}

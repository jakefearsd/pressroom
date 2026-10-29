package com.wikantik.pressroom.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@link WebloggerConfig} found while loading its layers is reported
 * through the logger, not printed to standard output.
 *
 * <p>The layers load before the logger exists (one of them may name the log4j2
 * configuration), so the class keeps what it found and logs it once logging is
 * up. A {@code System.out} line bypasses the log layout: it carries no level,
 * time or logger name, and is not one event a log reader can pick out. The
 * test suite's own classpath carries {@code roller-custom.properties}, so the
 * report here must say it was loaded.
 */
class WebloggerConfigBootReportTest {

    @Test
    void theReportSaysWhichCustomPropertiesWereLoaded() {
        List<String> report = WebloggerConfig.bootReport();

        assertTrue(report.stream().anyMatch(line -> line.contains("roller-custom.properties")),
                "the report names the custom properties file it loaded: " + report);
    }

    /** The old boot banner sent every production boot to a path that does not exist. */
    @Test
    void theReportPointsNowhereThatDoesNotExist() {
        List<String> report = WebloggerConfig.bootReport();

        assertFalse(report.isEmpty(), "loading reports what it found");
        assertTrue(report.stream().noneMatch(line -> line.contains("docs/testing")), report.toString());
        assertTrue(report.stream().noneMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("eclipse")),
                report.toString());
    }
}

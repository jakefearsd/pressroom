package com.wikantik.pressroom.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader's account page follows the admin conventions: labels above
 * fields, section headers in the 12px caps-label role, no obsolete table
 * attributes.
 */
class AccountJspTest {

    private static final Path ACCOUNT = Path.of("src/main/webapp/WEB-INF/jsps/core/Account.jsp");

    @Test
    void theAccountPageStacksItsLabelAndUsesTheCapsLabelHeaders() throws IOException {
        String jsp = Files.readString(ACCOUNT, StandardCharsets.UTF_8);
        assertFalse(jsp.contains("col-form-label"), "labels sit above their fields:\n" + jsp);
        assertTrue(jsp.contains("<label class=\"form-label\" for=\"screenName\">"), jsp);
        assertFalse(jsp.contains("class=\"h5\""), "section headers use the caps-label role:\n" + jsp);
        assertTrue(jsp.contains("<h2 class=\"section-head\">"), jsp);
        assertFalse(jsp.contains("width=\"100%\""), "no obsolete width attribute:\n" + jsp);
    }
}

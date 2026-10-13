/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.business.startup;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * SQL script runner, parses script and allows you to run it.
 * You can run the script multiple times if necessary.
 * Assumes that anything on an input line after "--" or ";" can be ignored,
 * except inside a dollar-quoted block ({@code $$ ... $$} or {@code $tag$ ... $tag$}),
 * where semicolons and "--" are ordinary content, not statement/comment syntax.
 */
public class SQLScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(SQLScriptRunner.class);

    /** Matches a dollar-quote delimiter: {@code $$} or {@code $tag$}. */
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$[A-Za-z0-9_]*\\$");

    private List<String> commands = new ArrayList<>();
    private List<String> messages = new ArrayList<>();
    private boolean      failed = false;
    private boolean      errors = false;


    /** Creates a new instance of SQLScriptRunner */
    public SQLScriptRunner(InputStream is) throws IOException {

        try (BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder command = new StringBuilder();
            String line;
            // Tag of the currently open dollar-quote (may be the empty string for
            // "$$"), or null when not inside one.
            String openTag = null;
            while ((line = in.readLine()) != null) {
                line = line.trim();

                // ignore lines starting with "--", unless we're inside an open
                // dollar-quote, where "--" is just content
                if (openTag != null || !line.startsWith("--")) {

                    // >= 0 (found at all), not > 0: a "--" at position 0 can
                    // never reach here anyway (the enclosing if's
                    // !line.startsWith("--") already routed that case away
                    // when openTag == null, which this branch requires), so
                    // this is behaviourally identical to the > 0 form it
                    // replaces -- but it does not depend on that coincidence
                    // to stay correct.
                    if (openTag == null && line.indexOf("--") >= 0) {
                        // trim comment off end of line
                        line = line.substring(0, line.indexOf("--")).trim();
                    }
                    // NOTE: this only looks at the INCOMING dollar-quote state (before
                    // this line's own delimiters take effect), not the state as of the
                    // "--" itself. So a delimiter that CLOSES on this same line, followed
                    // by a real "--" comment (e.g. "END $$; -- done"), is not stripped:
                    // "-- done" becomes part of the accumulated command text. Because
                    // lines are joined with a single space rather than a real newline,
                    // Postgres then reads that "--" as a comment running to the end of
                    // the (single-line) command string -- which silently swallows
                    // everything appended after it, including the next statement, rather
                    // than raising an error. This is not merely cosmetic: keep dollar-quote
                    // delimiters, and any trailing comment, off the same physical line as
                    // a terminating ";".

                    // add line to current command
                    command.append(line.trim());

                    // scan the newly appended line for dollar-quote delimiters,
                    // toggling openTag as they're found
                    openTag = scanDollarQuotes(line, openTag);

                    if (openTag == null && command.length() > 0 && command.charAt(command.length() - 1) == ';') {
                        // ";" is end of command, so add completed command to list
                        String cmd = command.substring(0, command.length() - 1);
                        String[] cmdArray = StringUtils.split(cmd);
                        cmd = StringUtils.join(cmdArray, " ");
                        commands.add(cmd);
                        command.setLength(0);
                    } else if (command.length() > 0) {
                        // still more command coming so add space
                        command.append(' ');
                    }
                }
            }
        }
    }

    /**
     * Scans {@code line} left to right for {@code $tag$} delimiters, toggling
     * dollar-quote state as it goes. Returns the tag of the dollar-quote left
     * open at the end of the line, or {@code null} if none is open.
     *
     * @param currentTag the tag already open before this line, or {@code null}
     */
    private static String scanDollarQuotes(String line, String currentTag) {
        Matcher m = DOLLAR_QUOTE.matcher(line);
        while (m.find()) {
            String tag = m.group().substring(1, m.group().length() - 1);
            if (currentTag == null) {
                // not inside a dollar-quote: this delimiter opens one
                currentTag = tag;
            } else if (tag.equals(currentTag)) {
                // matches the open delimiter's tag: this closes it
                currentTag = null;
            }
            // any other delimiter found while a different one is open is just
            // content (e.g. a "$foo$" literal inside a "$$ ... $$" body)
        }
        return currentTag;
    }
    
    
    /** Creates a new instance of SQLScriptRunner */
    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "Only tests call the path constructor, with scripts under target/test-classes; "
                    + "production runs migrations through the InputStream constructor "
                    + "(DatabaseInstaller.applyMigration).")
    public SQLScriptRunner(String scriptPath) throws IOException {
        this(new FileInputStream(scriptPath));
    }
    
    
    /** Number of SQL commands in script */
    public int getCommandCount() {
        return commands.size();
    }
    
    
    /**
     * The failures of the last run, each named by its statement's position and
     * never by its SQL (these reach the installer's pages; the SQL is logged).
     * Empty when every statement succeeded or the script has not run.
     */
    public List<String> getMessages() {
        return messages;
    }
    
    
    /** Returns true if last call to runScript() threw an exception */
    public boolean getFailed() {
        return failed;
    }
    
    
    /** Returns true if last run had any errors */
    public boolean getErrors() {
        return errors;
    }
    
    
    /** Run script, logs messages, and optionally throws exception on error */
    @SuppressFBWarnings(
            value = {"SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE", "SQL_INJECTION_JDBC"},
            justification = "SQLScriptRunner exists to execute the migration scripts under "
                    + "bin/db/migrations, which ship inside the artifact and are parsed by "
                    + "this class's own constructor -- non-constant SQL is the class's entire "
                    + "purpose, not an oversight. No user input reaches this method: its only "
                    + "production caller, DatabaseInstaller.applyMigration, builds the command "
                    + "list from files read off the classpath (MigrationCatalog.versions() / "
                    + "ClasspathDatabaseScriptProvider), and neither the web install wizard "
                    + "(InstallController) nor any other caller accepts caller-supplied SQL "
                    + "text. Parameterising this would break the migration runner, which is "
                    + "one of the three appliers of the migration chain.")
    public void runScript(
            Connection con, boolean stopOnError) throws SQLException {
        failed = false;
        errors = false;

        // A connection not in autocommit mode belongs to the caller (in
        // production, DatabaseInstaller.applyMigration, which commits the
        // whole script and its schema_migrations row together): this method
        // must never commit on its own initiative there, or a rollback the
        // caller issues after a later failure only undoes the one statement
        // that failed, not the script's earlier, already-committed work.
        // PostgreSQL also aborts the whole transaction after any failed
        // statement, so a tolerated failure (or stopOnError=false) needs a
        // savepoint per statement to keep the statements around it usable.
        // With autocommit on, neither problem exists -- PostgreSQL commits
        // each statement itself -- so this stays a no-op there, unchanged.
        boolean transactional = !con.getAutoCommit();

        // The messages reach the anonymous installer's pages, and a migration's
        // SQL carries the database user (its :app_user grants), so a message
        // names only the statement's position; the SQL text goes to the log.
        int number = 0;
        for (String command : commands) {
            number++;
            String position = "statement " + number + " of " + commands.size();

            Savepoint savepoint = null;
            try {
                if (transactional) {
                    savepoint = con.setSavepoint();
                }

                try (Statement stmt = con.createStatement()) {
                    stmt.executeUpdate(command);
                }

                if (transactional) {
                    con.releaseSavepoint(savepoint);
                }

                log.debug("SQL {} succeeded: [{}]", position, command);

            } catch (SQLException ex) {
                if (transactional) {
                    rollbackToSavepoint(con, savepoint, command);
                }
                if (command.contains("drop foreign key") || command.contains("drop index")) {
                    log.info("SQL {} failed and was ignored: [{}]: {}", position, command, ex.getMessage());
                    errorMessage("INFO: " + position + " failed and was ignored.");
                    continue;
                }
                errorMessage("ERROR: " + position + " failed; the details are in the server log.");
                if (stopOnError) {
                    // Rethrown, so the caller logs the exception with its trace
                    // (DatabaseInstaller.applyMigration); logging it here as well
                    // would print it twice.
                    log.error("SQL {} failed: [{}]", position, command);
                    failed = true;
                    throw ex;
                }
                log.error("SQL {} failed: [{}]", position, command, ex);
            }
        }
    }

    /**
     * Isolates one statement's failure from the rest of the caller's
     * transaction. A null {@code savepoint} (only possible if
     * {@code con.setSavepoint()} itself threw) has nothing to roll back to.
     */
    private void rollbackToSavepoint(Connection con, Savepoint savepoint, String command) {
        if (savepoint == null) {
            return;
        }
        try {
            con.rollback(savepoint);
        } catch (SQLException rollbackFailure) {
            log.error("Rollback to savepoint for [{}] failed", command, rollbackFailure);
        }
    }
    
    
    private void errorMessage(String msg) {
        messages.add(msg);
    }
    
    
    /**
     * Gets the commands.
     * 
     * @return the commands
     */
    public List<String> getCommands() {
        return commands;
    }

    /**
     * Sets the commands.
     * 
     * @param commands
     *            the new commands
     */
    public void setCommands(List<String> commands) {
        this.commands = commands;
    }
}

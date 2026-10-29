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
/*
 * TestUtils.java
 *
 * Created on April 6, 2006, 8:38 PM
 */

package com.wikantik.pressroom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.SpringWebloggerProvider;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.startup.WebloggerStartup;
import com.wikantik.pressroom.testing.PressroomDatabaseExtension;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.util.PressroomMessages;
import com.wikantik.pressroom.util.Utilities;
import com.wikantik.pressroom.WebloggerException;

/**
 * Utility class for unit test classes.
 */
public final class TestUtils {

    // Username prefix we are using (simplifies local testing)
    public static final String JUNIT_PREFIX = "junit_";

    /** Plaintext of {@link #TEST_PASSWORD_HASH}. */
    public static final String TEST_PASSWORD = "password";

    /**
     * A precomputed bcrypt hash of {@link #TEST_PASSWORD}.
     *
     * <p>Precomputed rather than encoded on demand because bcrypt is
     * deliberately slow and {@link #setupUser} has ~106 call sites; hashing per
     * call would add roughly ten seconds to the suite. Storing a real hash is
     * what lets a unit test authenticate a fixture user at all -- this used to
     * be the bare string {@code "password"}, written through the raw setter,
     * which no encoder could ever match.
     */
    public static final String TEST_PASSWORD_HASH =
            "{bcrypt}$2a$10$Vav4tnxZRN4O9Uh/gMr0Se5fn4grMKMdIaFYgd68hgGaRXs9UPfni";


    /**
     * The one real business tier the unit suite shares for the whole JVM,
     * built by {@link #setupWeblogger()} over a standalone Spring context
     * ({@link SpringWebloggerProvider#standalone()}). There is no static
     * locator any more: a test that needs the tier calls {@link #weblogger()},
     * and a class under test receives it by constructor, {@code init} or field
     * -- never by looking it up.
     */
    private static volatile SpringWebloggerProvider provider;

    public static synchronized void setupWeblogger() throws Exception {

        if (provider == null) {
            // Starts the PostgreSQL container, points Roller's config at it and
            // applies bin/db/migrations. Safe to call repeatedly.
            PressroomDatabaseExtension.ensureSchema();

            // do core services preparation
            WebloggerStartup.prepare();

            // do application bootstrapping -- the provider's bootstrap() also
            // runs initialize(), so there is no separate call here (a second
            // initialize() would start the task-scheduler thread twice)
            SpringWebloggerProvider standalone = SpringWebloggerProvider.standalone();
            standalone.bootstrap();
            provider = standalone;
        }
    }

    /**
     * The suite's bootstrapped business tier. Fails loudly if nothing has
     * called {@link #setupWeblogger()} yet -- the old static used to throw the
     * same way, and a null here would surface as an NPE three frames away.
     */
    public static Weblogger weblogger() {
        SpringWebloggerProvider p = provider;
        if (p == null) {
            throw new IllegalStateException(
                    "The test business tier is not up: call TestUtils.setupWeblogger() first");
        }
        return p.getWeblogger();
    }

    /** The provider behind {@link #weblogger()}, for the tests that exercise the provider itself. */
    public static SpringWebloggerProvider provider() {
        SpringWebloggerProvider p = provider;
        if (p == null) {
            throw new IllegalStateException(
                    "The test business tier is not up: call TestUtils.setupWeblogger() first");
        }
        return p;
    }

    public static void shutdownWeblogger() throws Exception {
        weblogger().shutdown();
    }

    /**
     * Convenience method that simulates the end of a typical session.
     * 
     * Normally this would be triggered by the end of the response in the webapp
     * but for unit tests we need to do this explicitly.
     * 
     * @param flush
     *            true if you want to flush changes to db before releasing
     */
    public static void endSession(boolean flush) throws Exception {
        if (flush) {
            TestUtils.weblogger().flush();
        }
        TestUtils.weblogger().release();
    }

    /**
     * Convenience method that creates a user and stores it.
     */
    public static User setupUser(String userName) throws Exception {

        // Set local name
        userName = JUNIT_PREFIX + userName;

        User testUser = new User();
        testUser.setUserName(userName);
        testUser.setPassword(TEST_PASSWORD_HASH);
        testUser.setScreenName("Test User Screen Name");
        testUser.setFullName("Test User");
        testUser.setEmailAddress("TestUser@dev.null");
        testUser.setLocale("en_US");
        testUser.setTimeZone("America/Los_Angeles");
        testUser.setDateCreated(new java.util.Date());
        testUser.setEnabled(Boolean.TRUE);

        // store the user
        UserManager mgr = TestUtils.weblogger().getUserManager();
        mgr.addUser(testUser);

        // flush to db
        TestUtils.weblogger().flush();

        // query for the user to make sure we return the persisted object
        User user = mgr.getUserByUserName(userName);

        if (user == null) {
            throw new WebloggerException("error inserting new user");
        }

        return user;
    }

    /**
     * Convenience method for removing a user.
     */
    public static void teardownUser(String userName) throws Exception {

        // lookup the user
        UserManager mgr = TestUtils.weblogger().getUserManager();
        User user = mgr.getUserByUserName(userName, null);

        // remove the user
        mgr.removeUser(user);

        // flush to db
        TestUtils.weblogger().flush();
    }

    /**
     * Convenience method that creates a weblog and stores it.
     */
    public static Weblog setupWeblog(String handle, User creator)
            throws Exception {

        Weblog testWeblog = new Weblog();
        testWeblog.setName("Test Weblog");
        testWeblog.setTagline("Test Weblog");
        testWeblog.setHandle(handle);
        testWeblog.setEmailAddress("testweblog@dev.null");
        testWeblog.setEditorTheme("journal");
        testWeblog.setLocale("en_US");
        testWeblog.setTimeZone("America/Los_Angeles");
        testWeblog.setDateCreated(new java.util.Date());
        testWeblog.setCreatorUserName(creator.getUserName());

        // add weblog
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        mgr.addWeblog(testWeblog);

        // flush to db
        TestUtils.weblogger().flush();

        // query for the new weblog and return it
        Weblog weblog = mgr.getWeblogByHandle(handle);

        if (weblog == null) {
            throw new WebloggerException("error setting up weblog");
        }

        return weblog;
    }

    /**
     * Convenience method for removing a weblog.
     */
    public static void teardownWeblog(String id) throws Exception {

        // lookup the weblog
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog weblog = mgr.getWeblog(id);

        // remove the weblog
        mgr.removeWeblog(weblog);

        // flush to db
        TestUtils.weblogger().flush();
    }

    /**
     * Convenience method for removing a permission.
     */
    public static void teardownPermissions(WeblogPermission perm)
            throws Exception {

        // remove all permissions
        UserManager mgr = TestUtils.weblogger().getUserManager();
        Weblog weblog = TestUtils.weblogger().getWeblogManager()
                .getWeblogByHandle(perm.getObjectId(), null);
        mgr.revokeWeblogPermission(weblog, mgr.getUserByUserName(perm.getUserName()),
                WeblogPermission.ALL_ACTIONS);

        // flush to db
        TestUtils.weblogger().flush();
    }

    /**
     * Convenience method for creating a weblog category.
     */
    public static WeblogCategory setupWeblogCategory(Weblog weblog, String name)
            throws Exception {

        WeblogEntryManager mgr = TestUtils.weblogger()
                .getWeblogEntryManager();

        WeblogCategory testCat = new WeblogCategory(weblog, name, null, null);
        mgr.saveWeblogCategory(testCat);

        // flush to db
        TestUtils.weblogger().flush();

        // query for object
        WeblogCategory cat = mgr.getWeblogCategory(testCat.getId());

        if (cat == null) {
            throw new WebloggerException("error setting up weblog category");
        }

        return cat;
    }

    /**
     * Convenience method for removing a weblog category.
     */
    public static void teardownWeblogCategory(String id) throws Exception {

        // lookup the cat
        WeblogEntryManager mgr = TestUtils.weblogger()
                .getWeblogEntryManager();
        WeblogCategory cat = mgr.getWeblogCategory(id);

        // remove the cat
        mgr.removeWeblogCategory(cat);

        // flush to db
        TestUtils.weblogger().flush();
    }

    /**
     * Convenience method for creating a published weblog entry.
     */
    public static WeblogEntry setupWeblogEntry(String anchor,
            WeblogCategory cat, Weblog weblog, User user) throws Exception {

        return TestUtils.setupWeblogEntry(anchor, cat, PubStatus.PUBLISHED,
                weblog, user);
    }

    /**
     * Convenience method for creating a published weblog entry with the blog's
     * default category
     */
    public static WeblogEntry setupWeblogEntry(String anchor, Weblog weblog,
            User user) throws Exception {

        return TestUtils.setupWeblogEntry(anchor, weblog.getWeblogCategories()
                .iterator().next(), PubStatus.PUBLISHED, weblog, user);
    }

    /**
     * Convenience method for creating a weblog entry with the blog's default
     * category, at a caller-supplied status.
     */
    public static WeblogEntry setupWeblogEntry(String anchor, Weblog weblog,
            User user, PubStatus status) throws Exception {

        return TestUtils.setupWeblogEntry(anchor, weblog.getWeblogCategories()
                .iterator().next(), status, weblog, user);
    }

    /**
     * Convenience method for creating a weblog entry
     */
    public static WeblogEntry setupWeblogEntry(String anchor,
            WeblogCategory cat, PubStatus status, Weblog weblog, User user)
            throws Exception {

        WeblogEntry testEntry = new WeblogEntry();
        testEntry.setTitle(anchor);
        testEntry.setLink("testEntryLink");
        testEntry.setText("blah blah entry");
        testEntry.setAnchor(anchor);
        testEntry.setPubTime(new java.sql.Timestamp(new java.util.Date()
                .getTime()));
        testEntry.setUpdateTime(new java.sql.Timestamp(new java.util.Date()
                .getTime()));
        testEntry.setStatus(status);
        testEntry.setWebsite(getManagedWebsite(weblog));
        testEntry.setCreatorUserName(getManagedUser(user).getUserName());
        testEntry.setCategory(cat);

        // store entry
        WeblogEntryManager mgr = TestUtils.weblogger()
                .getWeblogEntryManager();
        mgr.saveWeblogEntry(testEntry);

        // flush to db
        TestUtils.weblogger().flush();

        // query for object
        WeblogEntry entry = mgr.getWeblogEntry(testEntry.getId());

        if (entry == null) {
            throw new WebloggerException("error setting up weblog entry");
        }

        return entry;
    }

    /**
     * Convenience method for removing a weblog entry.
     */
    public static void teardownWeblogEntry(String id) throws Exception {

        // lookup the entry
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry entry = mgr.getWeblogEntry(id);

        // remove the entry
        mgr.removeWeblogEntry(entry);

        // flush to db
        TestUtils.weblogger().flush();
    }

    /**
     * Convenience method for creating a persisted, content-backed image
     * media file (jpeg content from the classpath test image /hawk.jpg) in
     * the weblog's root media directory.
     *
     * Mirrors the creation shape used throughout MediaFileTest: enable
     * uploads at runtime (createMediaFile refuses to store content
     * otherwise), look up the weblog's root MediaFileDirectory, build the
     * MediaFile with the classpath image as its input stream, create it,
     * flush, then re-fetch by id so callers get back the persisted object.
     */
    public static MediaFile setupImageMediaFile(Weblog weblog, String name)
            throws Exception {
        return setupImageMediaFile(weblog, name, null);
    }

    /**
     * Variant of {@link #setupImageMediaFile(Weblog, String)} that stores the
     * image in a named (non-default) directory, creating the directory when
     * it does not exist yet. Used by the private-directory tests, which need
     * files outside the default directory.
     */
    public static MediaFile setupImageMediaFile(Weblog weblog, String name,
            String directoryName) throws Exception {

        // Allow media uploads for this test -- and persist the change rather
        // than only mutating the in-memory map. WebloggerRuntimeConfig reads
        // this property back through the properties manager on every call, so
        // an unsaved flag survives only until the next session flush. That
        // made the fixture order-dependent: it worked when an earlier test
        // happened to have saved the property, and failed with "error setting
        // up media file" when this test ran first.
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.enabled").setValue("true");
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        Weblog managedWeblog = getManagedWebsite(weblog);
        MediaFileDirectory rootDirectory;
        if (directoryName == null) {
            rootDirectory = mfMgr.getDefaultMediaFileDirectory(managedWeblog);
        } else {
            rootDirectory = mfMgr.getMediaFileDirectoryByName(managedWeblog, directoryName);
            if (rootDirectory == null) {
                rootDirectory = mfMgr.createMediaFileDirectory(managedWeblog, directoryName);
                TestUtils.weblogger().flush();
            }
        }

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(managedWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setInputStream(TestUtils.class.getResourceAsStream("/hawk.jpg"));

        PressroomMessages messages = new PressroomMessages();
        mfMgr.createMediaFile(managedWeblog, mediaFile, messages);

        // flush to db
        TestUtils.weblogger().flush();

        // query for the new media file and return it
        MediaFile persisted = mfMgr.getMediaFile(mediaFile.getId());

        if (persisted == null) {
            // createMediaFile reports refusals through PressroomMessages rather
            // than throwing, so without this the fixture failed with a bare
            // "error setting up media file" and no clue which rule refused.
            StringBuilder why = new StringBuilder();
            messages.getErrors().forEachRemaining(m -> why.append(m.getKey()).append(' '));
            throw new WebloggerException("error setting up media file: " + why);
        }

        return persisted;
    }

    /**
     * Convenience method that returns managed copy of given user.
     */
    public static User getManagedUser(User user) throws WebloggerException {
        UserManager mgr = TestUtils.weblogger().getUserManager();
        return mgr.getUserByUserName(user.getUserName());
    }

    /**
     * Convenience method that returns managed copy of given website.
     */
    public static Weblog getManagedWebsite(Weblog website)
            throws WebloggerException {
        return TestUtils.weblogger().getWeblogManager()
                .getWeblog(website.getId());
    }

    /**
     * Convenience method that returns managed copy of given WeblogEntry.
     */
    public static WeblogEntry getManagedWeblogEntry(WeblogEntry weblogEntry)
            throws WebloggerException {
        return TestUtils.weblogger().getWeblogEntryManager()
                .getWeblogEntry(weblogEntry.getId());
    }

    /**
     * Convenience method that returns managed copy of given WeblogEntry.
     */
    public static WeblogCategory getManagedWeblogCategory(WeblogCategory cat)
            throws WebloggerException {
        return TestUtils.weblogger().getWeblogEntryManager()
                .getWeblogCategory(cat.getId());
    }

    public void testNothing() {
        // TODO: remove this method
    }

    /**
     * The weblog's category of that name, through the entry manager. This is
     * what {@code Weblog.getWeblogCategory(name)} used to do from inside the
     * entity (plan Task 16 moved it); fixtures ask the manager.
     */
    public static WeblogCategory categoryNamed(Weblog weblog, String name) throws WebloggerException {
        return TestUtils.weblogger().getWeblogEntryManager()
                .getWeblogCategoryByName(weblog, name);
    }

    /**
     * Replace a media file's tags from a space-separated string, through the
     * media manager -- what {@code MediaFile.setTagsAsString} used to do from
     * inside the entity (plan Task 16 moved the write to the manager).
     */
    public static void setMediaTags(MediaFile file, String tags) throws WebloggerException {
        TestUtils.weblogger().getMediaFileManager()
                .updateTags(file, Utilities.splitStringAsTags(tags));
    }
}

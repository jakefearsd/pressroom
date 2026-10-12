# New-Weblog Needs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an agent stand up and run a new Roller weblog entirely through `/api/v1` and `/api/mcp`: create it, configure it, import images by URL, write event dates, and remove values it set.

**Architecture:** The admin UI's checks move into shared rule classes in `ui.controllers` (`WeblogSettingsRules`, `WeblogCreationRules`, more `EntryFieldRules`), called by the JSP controllers and the API alike. New API routes (`POST /v1/weblogs`, `POST /v1/weblogs/{handle}/media/imports`) and new body fields (thirteen weblog settings, event dates, a `clear` list) reach MCP by the existing derivation. Image import is a new `business.fetch` package on Apache HttpClient 5. Its own DNS resolver refuses every non-public address, and the fetched image is stored through the same `MediaUploads.store` path a browser upload takes.

**Tech Stack:**
- Java 25 and Spring Boot 4.1 (Spring MVC 7) on Tomcat 11.
- Jackson 3 (`tools.jackson`).
- Apache HttpClient 5 (`httpclient5`, version managed by Spring Boot: 5.6.4).
- JUnit 6, Mockito and MockMvc for unit tests; the JDK's `com.sun.net.httpserver.HttpServer` for fetcher tests; it-selenium (the real packaged WAR) for `McpIT`.

**Spec:** `docs/superpowers/specs/2026-10-11-new-weblog-needs-design.md`

## Global Constraints

- **Routes:**
  - `POST /api/v1/weblogs` → MCP `weblogs create`.
  - `POST /api/v1/weblogs/{handle}/media/imports` → MCP `media import`.
  - Controllers are written relative to `/v1/...` (the `/api` prefix is the servlet mapping).
- **Weblog creation is not admin-only.** Its controller must not be `@AdminScoped`. Weblog settings (`PATCH /v1/weblogs/{handle}`) stay `@AdminScoped` with `GlobalPermission.ADMIN`.
- **`clear`:** null in a body always means "unchanged". `clear` is a list of field names.
  - **Clearable fields:**
    - Entry: `summary`, `metaTitle`, `searchDescription`, `canonicalUrl`, `featuredImageId`, `ogImageId`, `jsonLdType`, `geoLatitude`, `geoLongitude`, `eventStart`, `eventEnd`, `eventLocation`.
    - Weblog: `tagline`, `customDomain`, `icon`, `about`, `analyticsSiteId`, `analyticsShareUrl`, `newsletterListUuid`, `businessId`, `placeType`, `placeLocality`, `placeRegion`, `placeCountry`, `placeLat`, `placeLng`, `bookingUrl`.
    - Media: `altText`, `focalX`, `focalY`.
  - **Pairs:** `geoLatitude`/`geoLongitude`, `placeLat`/`placeLng`, `focalX`/`focalY`.
- **Fetcher policy:**
  - `http`/`https` only, ports 80/443 only, no user info, no proxy.
  - At most 3 redirects, each re-checked.
  - 5 s to connect, a 30 s deadline for the whole fetch.
  - Size cap `uploads.file.maxsize`.
  - JPEG/PNG/GIF/WebP by magic bytes; SVG refused.
  - Every non-public address refused (the ranges are in Task 7). There is no property, profile or flag that loosens it. Only a package-private constructor takes a looser policy, for tests.
- **Logger names:** `roller.media.import` (one INFO line per import: weblog, host, outcome, bytes, ms; never the URL's path or query).
- **Event times:** wall-clock times in the WEBLOG's zone, the format `pubTime` takes (`2026-08-02T19:30`), in the API and the editor.
- **Verbatim strings:**
  - `Weblog creation is turned off on this site.`
  - `This site allows one weblog per user.`
  - `That address is not on the public internet.`
  - `Not a JPEG, PNG, GIF or WebP image.`
  - `The image server took too long.`
  - `eventEnd must not be before eventStart.`
  - `clear applies only to updates.`
- **Dependencies:** the only new runtime dependency is `org.apache.httpcomponents.client5:httpclient5` (with its own `httpcore5` artifacts), no `<version>`.
- **i18n:** new message keys go in `app/src/main/resources/ApplicationResources.properties` only. Avoid apostrophes in them.
- **Repository rules (`CLAUDE.md`):**
  - TDD: watch each test fail first. Characterisation tests are the exception and say so in their javadoc.
  - Name every `@RequestParam`/`@PathVariable`.
  - SLF4J: a `Throwable` is the last argument and no `{}` consumes it.
  - PMD, CPD and SpotBugs: zero violations.
  - JaCoCo floors and ~90% diff coverage.
  - No code may assume a context path.
  - Commit each task; **never push**.
- **One Maven build at a time in this working tree.** Before every `mvn` run: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`. Wait while BUSY.
- **Unit test command shape:** `mvn -q -o -pl app test -Dtest='A,B' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- **Static gates before each commit:** `mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true`.
- Work on `master` (no branch, no worktree).

## Review Focus

1. **`clear` with the same field also sent as JSON `null`** (`{"featuredImageId": null, "clear": ["featuredImageId"]}`): null is "absent", so this clears and is not a "both set and cleared" 400. Task 2 (`ClearFieldsTest`) tests it.
2. **`clear` empty, or with duplicates or surrounding spaces** (`[]`, `["about","about"]`, `[" about "]`): an empty list is a no-op. Duplicates and spaces are tolerated, never a 400 or a 500. Task 2 tests it.
3. **Coordinates with more precision than the column** (`placeLat: 52.123456789`): stored and returned as 52.12, as the form would store them, not a 400. Task 2 tests it.
4. **An image server answering 200 with an empty body**: "Not a JPEG, PNG, GIF or WebP image.", never a 500 or an empty media file. Task 7 tests it.
5. **An event time with an offset or seconds** (`2026-08-02T19:30+02:00`, `2026-08-02T19:30:00`): the offset form is a 400 whose detail shows the expected format. The seconds form is accepted. Task 5 tests both.

---

## File Structure

**Create (production):**
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogSettingsRules.java`: settings checks shared by the settings form and `PATCH /v1/weblogs/{handle}`.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogCreationRules.java`: may-create, handle, name, theme, locale and zone checks, and the creation call, shared by the Create Weblog page and `POST /v1/weblogs`.
- `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/ClearFields.java`: validates a `clear` list.
- `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogCreateApi.java`: `POST /v1/weblogs`.
- `app/src/main/java/org/apache/roller/weblogger/business/fetch/PublicAddresses.java`: the address policy.
- `app/src/main/java/org/apache/roller/weblogger/business/fetch/ImageKind.java`: magic-byte sniffing.
- `app/src/main/java/org/apache/roller/weblogger/business/fetch/FetchRefusal.java`: a refused fetch, with the agent-facing detail.
- `app/src/main/java/org/apache/roller/weblogger/business/fetch/FetchedImage.java`: the result.
- `app/src/main/java/org/apache/roller/weblogger/business/fetch/RemoteImageFetcher.java`: the fetcher (Spring `@Component`).

**Modify (production):**
- `ui/controllers/editor/WeblogConfigController.java`, `WeblogConfigBean.java`.
- `ui/controllers/core/CreateWeblogController.java`.
- `ui/controllers/EntryFieldRules.java`, `ui/controllers/editor/EntryBean.java`, `EntryEditController.java`, `webapp/WEB-INF/jsps/editor/EntryEdit.jsp`.
- `ui/controllers/MediaUploads.java`.
- `ui/restapi/dto/AdminDtos.java`, `EntryDtos.java`, `MediaDtos.java`.
- `ui/restapi/v1/WeblogsApi.java`, `EntriesWriteApi.java`, `MediaApi.java`, `MetaApi.java`.
- `ui/restapi/mcp/McpToolCatalog.java`, `McpInstructions.java`.
- `ApplicationResources.properties`.
- `app/pom.xml`.
- `docs/api/README.md`, `docs/dev/api.md`.

**Tests:** one new test class per new production class, additions to the existing controller and API tests named in each task, and new `McpIT` tests.

---

### Task 1: Shared weblog settings rules, adopted by the settings form

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogSettingsRules.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogSettingsRulesTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigController.java` (`myValidate`, `validateBusiness`, `checkLength`, `withinRange` and their constants)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigBean.java` (`toDecimal` becomes `public static`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigControllerTest.java`
- Modify: `app/src/main/resources/ApplicationResources.properties`

**Interfaces:**
- Consumes: `CustomDomainRules.normalise/isWellFormed/isSiteHost`, `BusinessRules.isHttpUrl`, `UIUtils.getLocales()` (`List<Locale>`), `UIUtils.getTimeZones()` (`List<String>`), `WeblogConfigBean` getters/setters.
- Produces (Task 2 relies on these exact names):
  ```java
  public final class WeblogSettingsRules {
      public static final int MAX_TEXT = 255;
      public static final int MAX_PLACE_TEXT = 128;
      public record Violation(List<String> fields, String messageKey, String labelKey, Integer max,
                              String detail, boolean conflict) {
          public String field();                         // fields.get(0): the field the form marks
          public boolean involvesAny(Set<String> names); // any of fields in names
      }
      public record Context(String ownHandle, int maxEntries, String siteAbsoluteUrl,
                            BusinessManager businesses, WeblogManager weblogs) {
          public static Context current(String ownHandle, Weblogger weblogger);
      }
      public static List<Violation> check(WeblogConfigBean bean, Context context);
  }
  ```
  `check` normalises `bean.customDomain` in place (via `CustomDomainRules.normalise`), as the form always has.

- [ ] **Step 1: Characterisation tests for the form, written before anything moves**

  Read `WeblogConfigControllerTest` and list the rules of `WeblogConfigController.myValidate`/`validateBusiness` that already have a test asserting their field error. For every rule below without one, add a test in the file's existing style. Give each new test the javadoc line `Characterisation: written against the pre-extraction controller and expected to pass unchanged.` and assert that `ControllerTestFixture.invalidFields(model)` contains the field id:

  | Input on the bean | Field id expected |
  |---|---|
  | `entryDisplayCount` = site max + 1 | `weblog_bean_entryDisplayCount` |
  | `newsletterListUuid` = `"not-a-uuid"` | `weblog_bean_newsletterListUuid` |
  | `analyticsSiteId` = `"nope"` | `weblog_bean_analyticsSiteId` |
  | `analyticsShareUrl` = `"ftp://x"` | `weblog_bean_analyticsShareUrl` |
  | `businessId` unknown to the business manager | `weblog_bean_businessId` |
  | business manager `getBusiness` throws `WebloggerException` | `weblog_bean_businessId` |
  | `placeType` = `"Hotel"` | `weblog_bean_placeType` |
  | `placeType` = `"LodgingBusiness"`, `placeLocality` blank | `weblog_bean_placeLocality` |
  | `placeCountry` = `"USA"` | `weblog_bean_placeCountry` |
  | `placeLat` = `"91"`, `placeLng` = `"0"` | `weblog_bean_placeLat` |
  | `placeLat` blank, `placeLng` = `"10"` | `weblog_bean_placeLat` |
  | `placeLocality` of 129 characters | `weblog_bean_placeLocality` |
  | `bookingUrl` = `"mailto:x@example.com"` | `weblog_bean_bookingUrl` |
  | `customDomain` = `"not a host"` | `weblog_bean_customDomain` |
  | `customDomain` = the site's own host | `weblog_bean_customDomain` |
  | `customDomain` claimed by another weblog | `weblog_bean_customDomain` |

- [ ] **Step 2: Run them against the unchanged controller**

  Run: `mvn -q -o -pl app test -Dtest='WeblogConfigControllerTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS. These pin today's behaviour; if one fails, the test is wrong, not the controller.

- [ ] **Step 3: Write the failing rules test**

  ```java
  package org.apache.roller.weblogger.ui.controllers;

  import java.util.List;
  import java.util.Set;
  import org.apache.roller.weblogger.WebloggerException;
  import org.apache.roller.weblogger.business.BusinessManager;
  import org.apache.roller.weblogger.business.WeblogManager;
  import org.apache.roller.weblogger.pojos.Business;
  import org.apache.roller.weblogger.pojos.Weblog;
  import org.apache.roller.weblogger.ui.controllers.editor.WeblogConfigBean;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.*;
  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.when;

  /** The settings rules, without a request or a form: one bean in, the broken rules out. */
  class WeblogSettingsRulesTest {

      private BusinessManager businesses;
      private WeblogManager weblogs;
      private WeblogSettingsRules.Context context;

      @BeforeEach
      void setUp() {
          businesses = mock(BusinessManager.class);
          weblogs = mock(WeblogManager.class);
          context = new WeblogSettingsRules.Context("myblog", 30, "https://blog.example.com", businesses, weblogs);
      }

      private static WeblogConfigBean valid() {
          WeblogConfigBean bean = new WeblogConfigBean();
          bean.setEntryDisplayCount(15);
          bean.setLocale("en_US");
          bean.setTimeZone("Europe/Berlin");
          return bean;
      }

      private List<String> fieldsOf(WeblogConfigBean bean) {
          return WeblogSettingsRules.check(bean, context).stream().map(WeblogSettingsRules.Violation::field).toList();
      }

      @Test
      void aValidBeanBreaksNoRule() {
          assertEquals(List.of(), WeblogSettingsRules.check(valid(), context));
      }

      @Test
      void entryDisplayCountMustBeBetweenOneAndTheSiteMaximum() {
          WeblogConfigBean zero = valid();
          zero.setEntryDisplayCount(0);
          WeblogConfigBean over = valid();
          over.setEntryDisplayCount(31);
          assertEquals(List.of("entryDisplayCount"), fieldsOf(zero));
          assertEquals("entryDisplayCount must be between 1 and 30.",
                  WeblogSettingsRules.check(over, context).get(0).detail());
      }

      @Test
      void aLocaleOrTimeZoneTheFormDoesNotOfferIsRefused() {
          WeblogConfigBean bean = valid();
          bean.setLocale("xx_NOWHERE");
          bean.setTimeZone("Mars/Olympus_Mons");
          assertEquals(List.of("locale", "timeZone"), fieldsOf(bean));
      }

      @Test
      void iconAndAboutLongerThanTheirColumnAreRefusedWithTheirLabels() {
          WeblogConfigBean bean = valid();
          bean.setIcon("i".repeat(256));
          bean.setAbout("a".repeat(256));
          List<WeblogSettingsRules.Violation> found = WeblogSettingsRules.check(bean, context);
          assertEquals(List.of("icon", "about"), found.stream().map(WeblogSettingsRules.Violation::field).toList());
          assertEquals("websiteSettings.icon", found.get(0).labelKey());
          assertEquals(255, found.get(0).max());
          assertEquals("'icon' must be 255 characters or fewer.", found.get(0).detail());
      }

      @Test
      void aPlaceTypeWithoutALocalityInvolvesBothFields() {
          WeblogConfigBean bean = valid();
          bean.setPlaceType("LodgingBusiness");
          WeblogSettingsRules.Violation v = WeblogSettingsRules.check(bean, context).get(0);
          assertEquals("placeLocality", v.field());
          assertTrue(v.involvesAny(Set.of("placeType")));
      }

      @Test
      void halfACoordinatePairIsRefused() {
          WeblogConfigBean bean = valid();
          bean.setPlaceLng("10");
          assertEquals(List.of("placeLat"), fieldsOf(bean));
      }

      @Test
      void aCustomDomainAnotherWeblogHoldsIsAConflict() throws Exception {
          Weblog other = new Weblog();
          other.setHandle("other");
          when(weblogs.getWeblogByCustomDomain("travel.example.org")).thenReturn(other);
          WeblogConfigBean bean = valid();
          bean.setCustomDomain(" Travel.Example.org ");
          WeblogSettingsRules.Violation v = WeblogSettingsRules.check(bean, context).get(0);
          assertTrue(v.conflict());
          assertEquals("customDomain is already in use by another weblog.", v.detail());
          assertEquals("travel.example.org", bean.getCustomDomain(), "normalised onto the bean");
      }

      @Test
      void aBusinessLookupThatCannotRunIsNotOneThatPassed() throws Exception {
          when(businesses.getBusiness("b1")).thenThrow(new WebloggerException("db down"));
          WeblogConfigBean bean = valid();
          bean.setBusinessId("b1");
          assertEquals(List.of("businessId"), fieldsOf(bean));
      }

      @Test
      void aKnownBusinessPasses() throws Exception {
          when(businesses.getBusiness("b1")).thenReturn(new Business());
          WeblogConfigBean bean = valid();
          bean.setBusinessId("b1");
          assertEquals(List.of(), fieldsOf(bean));
      }

      @Test
      void anAnalyticsShareUrlMustBeHttpAndFitItsColumn() {
          WeblogConfigBean ftp = valid();
          ftp.setAnalyticsShareUrl("ftp://x");
          WeblogConfigBean longUrl = valid();
          longUrl.setAnalyticsShareUrl("https://x/" + "a".repeat(250));
          assertEquals(List.of("analyticsShareUrl"), fieldsOf(ftp));
          assertEquals(List.of("analyticsShareUrl"), fieldsOf(longUrl));
      }
  }
  ```
  If `Business` has no public no-argument constructor, build one the way `WeblogConfigControllerTest` does.

- [ ] **Step 4: Run it to see it fail**

  Run: `mvn -q -o -pl app test -Dtest='WeblogSettingsRulesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: compilation failure, because `WeblogSettingsRules` does not exist.

- [ ] **Step 5: Implement `WeblogSettingsRules`**

  First make `WeblogConfigBean.toDecimal` `public static` (its javadoc stays).

  ```java
  package org.apache.roller.weblogger.ui.controllers;

  import java.math.BigDecimal;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Locale;
  import java.util.Set;
  import java.util.regex.Pattern;
  import org.apache.commons.lang3.StringUtils;
  import org.apache.roller.weblogger.WebloggerException;
  import org.apache.roller.weblogger.business.BusinessManager;
  import org.apache.roller.weblogger.business.WeblogManager;
  import org.apache.roller.weblogger.business.Weblogger;
  import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
  import org.apache.roller.weblogger.pojos.Weblog;
  import org.apache.roller.weblogger.ui.controllers.editor.WeblogConfigBean;
  import org.apache.roller.weblogger.ui.controllers.util.UIUtils;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;

  /**
   * The rules a weblog's settings must satisfy: one home for the settings form
   * ({@code WeblogConfigController}) and {@code PATCH /api/v1/weblogs/{handle}},
   * so the two cannot drift. Each rule judges the settings as they will be
   * after the change. A {@link Violation} names every field its rule involves:
   * the form marks the first, and the API reports a violation only when its
   * request touched one of them, so a value stored before a rule existed
   * cannot block an unrelated change.
   */
  public final class WeblogSettingsRules {

      private static final Logger log = LoggerFactory.getLogger(WeblogSettingsRules.class);

      /** icon, about, analytics_share_url and booking_url are varchar(255). */
      public static final int MAX_TEXT = 255;
      /** place_locality and place_region are varchar(128). */
      public static final int MAX_PLACE_TEXT = 128;

      private static final Pattern UUID = Pattern.compile(
              "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
      private static final Pattern HTTP_URL = Pattern.compile("(?i)^https?://.*");
      private static final Pattern COUNTRY = Pattern.compile("^[A-Za-z]{2}$");
      private static final BigDecimal MAX_LAT = new BigDecimal(90);
      private static final BigDecimal MAX_LNG = new BigDecimal(180);
      private static final String LODGING_BUSINESS = "LodgingBusiness";

      /**
       * One broken rule. {@code fields} are bean property names, the first the
       * one the form marks; {@code labelKey} and {@code max} are set only for a
       * too-long value, whose message takes the field's label and the limit.
       */
      public record Violation(List<String> fields, String messageKey, String labelKey, Integer max,
                              String detail, boolean conflict) {
          public Violation {
              fields = List.copyOf(fields);
          }

          public String field() {
              return fields.get(0);
          }

          public boolean involvesAny(Set<String> names) {
              return fields.stream().anyMatch(names::contains);
          }
      }

      /** What the rules need beyond the bean: the weblog's own handle, the site, and two lookups. */
      public record Context(String ownHandle, int maxEntries, String siteAbsoluteUrl,
                            BusinessManager businesses, WeblogManager weblogs) {
          public static Context current(String ownHandle, Weblogger weblogger) {
              return new Context(ownHandle,
                      WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries"),
                      WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"),
                      weblogger.getBusinessManager(), weblogger.getWeblogManager());
          }
      }

      private WeblogSettingsRules() {
      }

      public static List<Violation> check(WeblogConfigBean bean, Context context) {
          List<Violation> found = new ArrayList<>();
          int count = bean.getEntryDisplayCount();
          if (count < 1 || (context.maxEntries() > 0 && count > context.maxEntries())) {
              found.add(simple("entryDisplayCount", "websiteSettings.error.entryDisplayCount",
                      "entryDisplayCount must be between 1 and " + context.maxEntries() + "."));
          }
          String locale = StringUtils.trimToNull(bean.getLocale());
          if (locale != null && UIUtils.getLocales().stream().map(Locale::toString).noneMatch(locale::equals)) {
              found.add(simple("locale", "websiteSettings.locale.invalid",
                      "locale must be one the settings form offers, for example en_US."));
          }
          String zone = StringUtils.trimToNull(bean.getTimeZone());
          if (zone != null && !UIUtils.getTimeZones().contains(zone)) {
              found.add(simple("timeZone", "websiteSettings.timeZone.invalid",
                      "timeZone must be a time zone id, for example Europe/Berlin."));
          }
          tooLong("icon", "websiteSettings.icon", bean.getIcon(), MAX_TEXT, found);
          tooLong("about", "websiteSettings.about", bean.getAbout(), MAX_TEXT, found);
          mustBeUuid("newsletterListUuid", bean.getNewsletterListUuid(), found);
          mustBeUuid("analyticsSiteId", bean.getAnalyticsSiteId(), found);
          String shareUrl = StringUtils.trimToNull(bean.getAnalyticsShareUrl());
          if (shareUrl != null && !HTTP_URL.matcher(shareUrl).matches()) {
              found.add(simple("analyticsShareUrl", "websiteSettings.analyticsShareUrl.invalid",
                      "analyticsShareUrl must be an http or https URL."));
          } else {
              tooLong("analyticsShareUrl", "websiteSettings.analyticsShareUrl", shareUrl, MAX_TEXT, found);
          }
          checkBusiness(bean, context, found);
          checkPlace(bean, found);
          checkCustomDomain(bean, context, found);
          return found;
      }

      private static void checkBusiness(WeblogConfigBean bean, Context context, List<Violation> found) {
          String businessId = StringUtils.trimToNull(bean.getBusinessId());
          if (businessId == null) {
              return;
          }
          boolean known;
          try {
              known = context.businesses().getBusiness(businessId) != null;
          } catch (WebloggerException e) {
              // A lookup that could not run is not one that passed.
              log.error("Error checking business", e);
              known = false;
          }
          if (!known) {
              found.add(simple("businessId", "websiteSettings.business.invalid", "No such business."));
          }
      }

      private static void checkPlace(WeblogConfigBean bean, List<Violation> found) {
          String placeType = StringUtils.trimToNull(bean.getPlaceType());
          if (placeType != null && !LODGING_BUSINESS.equals(placeType)) {
              found.add(simple("placeType", "websiteSettings.placeType.invalid",
                      "placeType must be LodgingBusiness."));
          } else if (placeType != null && StringUtils.isBlank(bean.getPlaceLocality())) {
              found.add(new Violation(List.of("placeLocality", "placeType"), "websiteSettings.placeLocality.required",
                      null, null, "A place needs a placeLocality (a town or city).", false));
          }
          String country = StringUtils.trimToNull(bean.getPlaceCountry());
          if (country != null && !COUNTRY.matcher(country).matches()) {
              found.add(simple("placeCountry", "websiteSettings.placeCountry.invalid",
                      "placeCountry must be a two-letter country code."));
          }
          String lat = StringUtils.trimToNull(bean.getPlaceLat());
          String lng = StringUtils.trimToNull(bean.getPlaceLng());
          if (!withinRange(lat, MAX_LAT) || (lat == null && lng != null)) {
              found.add(new Violation(List.of("placeLat", "placeLng"), "websiteSettings.placeLat.invalid", null, null,
                      "placeLat must be between -90 and 90, given together with placeLng.", false));
          }
          if (!withinRange(lng, MAX_LNG) || (lng == null && lat != null)) {
              found.add(new Violation(List.of("placeLng", "placeLat"), "websiteSettings.placeLng.invalid", null, null,
                      "placeLng must be between -180 and 180, given together with placeLat.", false));
          }
          tooLong("placeLocality", "websiteSettings.placeLocality", bean.getPlaceLocality(), MAX_PLACE_TEXT, found);
          tooLong("placeRegion", "websiteSettings.placeRegion", bean.getPlaceRegion(), MAX_PLACE_TEXT, found);
          String bookingUrl = StringUtils.trimToNull(bean.getBookingUrl());
          if (bookingUrl != null && !BusinessRules.isHttpUrl(bookingUrl)) {
              found.add(simple("bookingUrl", "websiteSettings.bookingUrl.invalid",
                      "bookingUrl must be an http or https URL."));
          } else {
              tooLong("bookingUrl", "websiteSettings.bookingUrl", bookingUrl, MAX_TEXT, found);
          }
      }

      private static void checkCustomDomain(WeblogConfigBean bean, Context context, List<Violation> found) {
          String customDomain = CustomDomainRules.normalise(bean.getCustomDomain());
          bean.setCustomDomain(customDomain);
          if (customDomain == null) {
              return;
          }
          if (!CustomDomainRules.isWellFormed(customDomain)) {
              found.add(simple("customDomain", "websiteSettings.customDomain.invalid",
                      "customDomain must be a well-formed hostname."));
          } else if (CustomDomainRules.isSiteHost(customDomain, context.siteAbsoluteUrl())) {
              // Resolving the site's own host to one weblog loops every request (see WeblogsApi's history).
              found.add(simple("customDomain", "websiteSettings.customDomain.isSiteHost",
                      "customDomain must not be the site's own hostname."));
          } else {
              try {
                  Weblog claimant = context.weblogs().getWeblogByCustomDomain(customDomain);
                  if (claimant != null && !claimant.getHandle().equals(context.ownHandle())) {
                      found.add(new Violation(List.of("customDomain"), "websiteSettings.customDomain.taken",
                              null, null, "customDomain is already in use by another weblog.", true));
                  }
              } catch (WebloggerException e) {
                  log.error("Error checking custom domain", e);
                  found.add(simple("customDomain", "websiteSettings.customDomain.invalid",
                          "customDomain could not be checked; try again."));
              }
          }
      }

      private static void mustBeUuid(String field, String raw, List<Violation> found) {
          String value = StringUtils.trimToNull(raw);
          if (value != null && !UUID.matcher(value).matches()) {
              found.add(simple(field, "websiteSettings." + field + ".invalid", field + " must be a UUID."));
          }
      }

      private static void tooLong(String field, String labelKey, String raw, int max, List<Violation> found) {
          String value = StringUtils.trimToNull(raw);
          if (value != null && value.length() > max) {
              found.add(new Violation(List.of(field), "businesses.error.tooLong", labelKey, max,
                      "'" + field + "' must be " + max + " characters or fewer.", false));
          }
      }

      /** True when blank, or a plain decimal whose magnitude is at most {@code limit}. */
      private static boolean withinRange(String text, BigDecimal limit) {
          if (text == null) {
              return true;
          }
          BigDecimal value = WeblogConfigBean.toDecimal(text);
          return value != null && value.abs().compareTo(limit) <= 0;
      }

      private static Violation simple(String field, String messageKey, String detail) {
          return new Violation(List.of(field), messageKey, null, null, detail, false);
      }
  }
  ```
  `websiteSettings.newsletterListUuid.invalid` and `websiteSettings.analyticsSiteId.invalid` are the keys the controller used; `mustBeUuid` builds exactly those. If `websiteSettings.analyticsShareUrl` (a label) is not in `ApplicationResources.properties`, add it. Add the two new keys:

  ```properties
  websiteSettings.locale.invalid=Choose a locale from the list.
  websiteSettings.timeZone.invalid=Choose a time zone from the list.
  ```

- [ ] **Step 6: Run the rules test**

  Run: `mvn -q -o -pl app test -Dtest='WeblogSettingsRulesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS.

- [ ] **Step 7: Point the controller at the rules**

  In `WeblogConfigController`, replace the bodies of `myValidate` and `validateBusiness`, and delete `checkLength`, `withinRange`, `UUID_PATTERN`, `MAX_LAT`, `MAX_LNG`, `COUNTRY`, `MAX_PLACE_TEXT` and `MAX_BOOKING_URL`:

  ```java
      private void myValidate(WeblogConfigBean bean, HttpServletRequest request, Model model) {
          // The real action weblog's handle, never bean.getHandle(): the "bean." prefix binds
          // bean.handle from POST data the JSP never renders (I4), so it cannot be trusted here.
          WeblogSettingsRules.Context context =
                  WeblogSettingsRules.Context.current(getActionWeblog(request).getHandle(), weblogger);
          for (WeblogSettingsRules.Violation v : WeblogSettingsRules.check(bean, context)) {
              String fieldId = "weblog_bean_" + v.field();
              if (v.labelKey() == null) {
                  addFieldError(model, fieldId, v.messageKey(), request);
              } else {
                  addFieldError(model, fieldId, v.messageKey(),
                          new Object[] {getText(v.labelKey(), request), v.max()}, request);
              }
          }
          // Only warn about the zone when the save is actually going to happen (see the history of this check).
          if (!hasErrors(model) && CustomDomainRules.isOutsideCertZones(bean.getCustomDomain(),
                  WebloggerConfig.getProperty("vhost.cert.zones"))) {
              model.addAttribute("customDomainWarning", bean.getCustomDomain());
          }
      }
  ```
  Remove imports that are no longer used. Keep the comments about I4 and the cert-zone warning, condensed as above.

- [ ] **Step 8: Run the controller and rules tests**

  Run: `mvn -q -o -pl app test -Dtest='WeblogConfig*Test,WeblogSettingsRulesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS, every Step 1 characterisation test included. If one fails, the extraction changed behaviour: fix the rules, not the test. The one intended change is that `entryDisplayCount` below 1 is now also refused. Add a controller test for it.

- [ ] **Step 9: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogSettingsRules.java \
          app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogSettingsRulesTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigController.java \
          app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigBean.java \
          app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigControllerTest.java \
          app/src/main/resources/ApplicationResources.properties
  git commit -m "refactor(settings): one home for the weblog settings rules, shared by the form"
  ```

---

### Task 2: The thirteen settings and `clear` through `PATCH /v1/weblogs/{handle}`

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/ClearFields.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/dto/ClearFieldsTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java` (`WeblogView`, `WeblogPatch`, `toView`, new `applyPatch`, constants)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApi.java` (`update`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApiTest.java`; keep `WeblogsApiCustomDomainTest` green
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java` (the `weblogs update` action description only)

**Interfaces:**
- Consumes (Task 1): `WeblogSettingsRules.check(WeblogConfigBean, Context)`, `Context.current(String, Weblogger)`, `Violation.involvesAny/conflict/detail`. Also `WeblogConfigBean.copyFrom(Weblog)` and `copyTo(Weblog, BusinessManager)`.
- Produces (Tasks 6 and 4 rely on these):
  ```java
  public final class ClearFields {
      /** Names to clear, validated; empty for a null or empty list. Throws ApiException 400. */
      public static Set<String> validate(List<String> clear, Record body,
                                         List<String> clearable, List<List<String>> pairs);
  }
  // AdminDtos
  public static final List<String> WEBLOG_CLEARABLE;      // the Weblog list in Global Constraints, in that order
  public static final List<List<String>> WEBLOG_PAIRS;    // List.of(List.of("placeLat", "placeLng"))
  public record WeblogView(String id, String handle, String name, String tagline, String emailAddress,
          String locale, String timeZone, int entryDisplayCount, boolean active, String editorTheme,
          String creatorUserName, String customDomain, String icon, String about, String analyticsSiteId,
          String analyticsShareUrl, String newsletterListUuid, String businessId, String placeType,
          String placeLocality, String placeRegion, String placeCountry, Double placeLat, Double placeLng,
          String bookingUrl) { }
  public record WeblogPatch(String name, String tagline, String emailAddress, String locale, String timeZone,
          Integer entryDisplayCount, Boolean active, String customDomain, String icon, String about,
          String analyticsSiteId, String analyticsShareUrl, String newsletterListUuid, String businessId,
          String placeType, String placeLocality, String placeRegion, String placeCountry, Double placeLat,
          Double placeLng, String bookingUrl, List<String> clear) { }
  /** Applies the patch and the clears onto a form bean loaded from the weblog; returns the touched names. */
  public static Set<String> applyPatch(WeblogConfigBean bean, WeblogPatch patch, Set<String> cleared);
  ```

- [ ] **Step 1: Write the failing `ClearFieldsTest`**

  ```java
  package org.apache.roller.weblogger.ui.restapi.dto;

  import java.util.Arrays;
  import java.util.List;
  import java.util.Set;
  import org.apache.roller.weblogger.ui.restapi.ApiException;
  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.*;

  class ClearFieldsTest {

      record Body(String about, Double lat, Double lng, List<String> clear) { }

      private static final List<String> CLEARABLE = List.of("about", "lat", "lng");
      private static final List<List<String>> PAIRS = List.of(List.of("lat", "lng"));

      private static Set<String> validate(Body body) {
          return ClearFields.validate(body.clear(), body, CLEARABLE, PAIRS);
      }

      private static String refusal(Body body) {
          ApiException e = assertThrows(ApiException.class, () -> validate(body));
          assertEquals(400, e.getStatus().value());
          return e.getMessage();
      }

      @Test
      void nullOrEmptyClearsNothing() {
          assertEquals(Set.of(), validate(new Body("x", null, null, null)));
          assertEquals(Set.of(), validate(new Body("x", null, null, List.of())));
      }

      @Test
      void aFieldSentAsNullAndClearedIsCleared() {
          // null is "absent", so naming the field in clear is not "both set and cleared"
          assertEquals(Set.of("about"), validate(new Body(null, null, null, List.of("about"))));
      }

      @Test
      void duplicatesAndSpacesAreTolerated() {
          assertEquals(Set.of("about"), validate(new Body(null, null, null, List.of("about", " about "))));
      }

      @Test
      void aNameThatCannotBeClearedIsRefusedWithTheClearableList() {
          assertTrue(refusal(new Body(null, null, null, List.of("title"))).contains("Clearable: about, lat, lng."));
      }

      @Test
      void aNullNameIsRefusedNotAnNpe() {
          refusal(new Body(null, null, null, Arrays.asList((String) null)));
      }

      @Test
      void halfAPairIsRefused() {
          assertEquals("lat and lng are cleared together.", refusal(new Body(null, null, null, List.of("lat"))));
      }

      @Test
      void aFieldBothSetAndClearedIsRefused() {
          assertEquals("about is both set and cleared.", refusal(new Body("x", null, null, List.of("about"))));
      }

      @Test
      void aWholePairClears() {
          assertEquals(Set.of("lat", "lng"), validate(new Body(null, null, null, List.of("lat", "lng"))));
      }
  }
  ```
  Use the status accessor `ApiException` actually has (read the class); the assertion is that it is 400.

- [ ] **Step 2: Run it to see it fail**

  Run: `mvn -q -o -pl app test -Dtest='ClearFieldsTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: compilation failure (no `ClearFields`).

- [ ] **Step 3: Implement `ClearFields`**

  ```java
  package org.apache.roller.weblogger.ui.restapi.dto;

  import java.lang.reflect.RecordComponent;
  import java.util.HashSet;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Set;
  import org.apache.roller.weblogger.ui.restapi.ApiException;

  /**
   * The {@code clear} list of an update body: the fields to remove. A null
   * field in a body always means "unchanged", so an agent that sends null for
   * every argument it meant to skip never erases anything; removing a value is
   * this explicit list. One rule for every resource that has one.
   */
  public final class ClearFields {

      private ClearFields() {
      }

      public static Set<String> validate(List<String> clear, Record body,
                                         List<String> clearable, List<List<String>> pairs) {
          if (clear == null || clear.isEmpty()) {
              return Set.of();
          }
          Set<String> names = new LinkedHashSet<>();
          for (String raw : clear) {
              String name = raw == null ? "" : raw.trim();
              if (!clearable.contains(name)) {
                  throw ApiException.badRequest("\"" + abbreviate(name) + "\" cannot be cleared. Clearable: "
                          + String.join(", ", clearable) + ".");
              }
              names.add(name);
          }
          for (List<String> pair : pairs) {
              if (names.contains(pair.get(0)) != names.contains(pair.get(1))) {
                  throw ApiException.badRequest(pair.get(0) + " and " + pair.get(1) + " are cleared together.");
              }
          }
          for (String set : setFields(body)) {
              if (names.contains(set)) {
                  throw ApiException.badRequest(set + " is both set and cleared.");
              }
          }
          return Set.copyOf(names);
      }

      /** The body's components that carry a value, {@code clear} itself excluded. */
      static Set<String> setFields(Record body) {
          Set<String> set = new HashSet<>();
          for (RecordComponent component : body.getClass().getRecordComponents()) {
              if ("clear".equals(component.getName())) {
                  continue;
              }
              try {
                  if (component.getAccessor().invoke(body) != null) {
                      set.add(component.getName());
                  }
              } catch (ReflectiveOperationException e) {
                  throw new IllegalStateException("Cannot read " + component.getName(), e);
              }
          }
          return set;
      }

      private static String abbreviate(String name) {
          return name.length() <= 64 ? name : name.substring(0, 64) + "...";
      }
  }
  ```
  `ClearFieldsTest.Body` is a nested record in a test class: if `getAccessor().invoke` throws `IllegalAccessException` for it, make the test record `public`. The production DTO records are public nested records of public classes.

- [ ] **Step 4: Run `ClearFieldsTest`**

  Expected: PASS.

- [ ] **Step 5: Write the failing `WeblogsApiTest` cases**

  `update` now reads `WebloggerRuntimeConfig` on every PATCH (through `Context.current`). Wrap the PATCH tests (new and existing) in a stub. Add a helper to the test class:
  ```java
  private static org.mockito.MockedStatic<org.apache.roller.weblogger.config.WebloggerRuntimeConfig> siteConfig() {
      var config = mockStatic(org.apache.roller.weblogger.config.WebloggerRuntimeConfig.class);
      config.when(() -> org.apache.roller.weblogger.config.WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries"))
            .thenReturn(30);
      config.when(() -> org.apache.roller.weblogger.config.WebloggerRuntimeConfig
            .getPropertyWithConfigFallback("site.absoluteurl")).thenReturn("https://blog.example.com");
      return config;
  }
  ```
  Also stub `weblogger.getBusinessManager()` with a mock `BusinessManager` in `mockedWeblogger()`. Existing PATCH tests that start failing for want of these stubs get the stubs, nothing else.

  Add these tests. Each performs a PATCH with `requestAttr("actionWeblog", weblog)` inside `try (var config = siteConfig(); var cache = mockStatic(CacheManager.class))`:
  ```java
  @Test
  void patchSetsTheNewSettingsAndGetShowsThem() throws Exception {
      // body: {"about":"A blog","icon":"https://x/i.png","analyticsSiteId":"0b5d3f9e-1c2a-4e7b-9a6d-2f1e0c9b8a7d",
      //        "placeType":"LodgingBusiness","placeLocality":"Encinitas","placeCountry":"us",
      //        "placeLat":33.04,"placeLng":-117.29,"bookingUrl":"https://book.example.com"}
      // expect 200; JSON has about, icon, analyticsSiteId, placeType, placeLocality, placeCountry "US",
      // placeLat 33.04, placeLng -117.29, bookingUrl; and the Weblog object carries the same values
  }

  @Test
  void aCoordinateWithMorePrecisionThanTheColumnIsStoredAtTwoDecimals() throws Exception {
      // {"placeType":"LodgingBusiness","placeLocality":"Berlin","placeLat":52.123456789,"placeLng":13.4049}
      // expect 200; weblog.getPlaceLat() equals new BigDecimal("52.12"); JSON placeLat 52.12
  }

  @Test
  void aNonFiniteCoordinateIsRefused() throws Exception {
      // call AdminDtos.applyPatch directly with placeLat Double.NaN: ApiException 400 "placeLat must be a finite number."
  }

  @Test
  void aRuleTheRequestBreaksIsA400AndChangesNothing() throws Exception {
      // {"placeType":"Hotel"} -> 400, detail "placeType must be LodgingBusiness."; weblog.getPlaceType() still null;
      // verify(weblogManager, never()).saveWeblog(any())
  }

  @Test
  void setsOnlyPlaceTypeOnAWeblogWithNoLocalityIsRefused() throws Exception {
      // {"placeType":"LodgingBusiness"} on a weblog with no locality -> 400 "A place needs a placeLocality (a town or city)."
  }

  @Test
  void aStoredValueThatBreaksARuleDoesNotBlockAnUnrelatedChange() throws Exception {
      // weblog.setAnalyticsSiteId("legacy-not-a-uuid"); PATCH {"tagline":"New"} -> 200, tagline saved
  }

  @Test
  void clearRemovesValuesAndPlacePairClearsTogether() throws Exception {
      // weblog has about, placeType+locality, placeLat/Lng set;
      // {"clear":["about","placeType","placeLocality","placeLat","placeLng"]} -> 200; all five null on weblog and in JSON
  }

  @Test
  void clearingTheLocalityWhileThePlaceTypeStaysIsRefused() throws Exception {
      // weblog has placeType LodgingBusiness + locality; {"clear":["placeLocality"]} -> 400; locality unchanged
  }

  @Test
  void anUnknownBusinessIsA400() throws Exception {
      // businessManager.getBusiness("nope") returns null; {"businessId":"nope"} -> 400 "No such business."
  }

  @Test
  void anUnknownTimeZoneIsA400() throws Exception {
      // {"timeZone":"Mars/Olympus_Mons"} -> 400 "timeZone must be a time zone id, for example Europe/Berlin."
  }
  ```
  Write each test out in full with the `mockMvc(...).perform(patch(...).contentType(MediaType.APPLICATION_JSON).content(json))` shape the file already uses. The comments above give the exact inputs and expectations.

- [ ] **Step 6: Run them to see them fail**

  Run: `mvn -q -o -pl app test -Dtest='WeblogsApiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: compilation failure or FAIL (no new fields yet).

- [ ] **Step 7: Extend `AdminDtos`**

  Replace `WeblogView`/`WeblogPatch` with the shapes in Interfaces. Put springdoc `@Schema` descriptions on the new `WeblogPatch` components: `placeType` ("LodgingBusiness, or omit; needs placeLocality"), `placeCountry` ("Two-letter country code"), `placeLat`/`placeLng` ("Decimal degrees, given as a pair; stored to two decimals"), `businessId` ("Id of an existing business profile"), and `clear` ("Fields to remove: tagline, customDomain, icon, about, analyticsSiteId, analyticsShareUrl, newsletterListUuid, businessId, placeType, placeLocality, placeRegion, placeCountry, placeLat with placeLng, bookingUrl. Null never removes anything."). `toView` fills the new view fields from `getIconPath()`, `getAbout()`, `getAnalyticsSiteId()`, `getAnalyticsShareUrl()`, `getNewsletterListUuid()`, `getBusiness() == null ? null : getBusiness().getId()`, the place getters (`BigDecimal.doubleValue()` for lat/lng, null-safe) and `getBookingUrl()`.

  Add:
  ```java
  public static final List<String> WEBLOG_CLEARABLE = List.of("tagline", "customDomain", "icon", "about",
          "analyticsSiteId", "analyticsShareUrl", "newsletterListUuid", "businessId", "placeType",
          "placeLocality", "placeRegion", "placeCountry", "placeLat", "placeLng", "bookingUrl");
  public static final List<List<String>> WEBLOG_PAIRS = List.of(List.of("placeLat", "placeLng"));

  /**
   * Lays a PATCH over the settings form's bean, loaded from the weblog, so the
   * form's own copyTo stores the result: the API and the form cannot store a
   * value differently. Column and blank checks that were here before stay here
   * (name, tagline, emailAddress, locale, timeZone lengths); everything the
   * settings rules judge is left to WeblogSettingsRules. Returns the names the
   * request touched (set or cleared), which is what the caller reports
   * violations against.
   */
  public static Set<String> applyPatch(WeblogConfigBean bean, WeblogPatch patch, Set<String> cleared) {
      if (patch.name() != null) {
          String name = patch.name().trim();
          if (name.isBlank()) {
              throw ApiException.badRequest("name cannot be blank.");
          }
          ColumnLimits.requireMaxLength("name", name, ColumnLimits.WEBLOG_NAME);
          bean.setName(name);
      }
      if (patch.tagline() != null) {
          ColumnLimits.requireMaxLength("tagline", patch.tagline(), ColumnLimits.TAGLINE);
          bean.setTagline(patch.tagline());
      }
      if (patch.emailAddress() != null) {
          ColumnLimits.requireMaxLength("emailAddress", patch.emailAddress(), ColumnLimits.WEBLOG_EMAIL_ADDRESS);
          bean.setEmailAddress(patch.emailAddress());
      }
      if (patch.locale() != null) {
          ColumnLimits.requireMaxLength("locale", patch.locale(), ColumnLimits.LOCALE);
          bean.setLocale(patch.locale());
      }
      if (patch.timeZone() != null) {
          ColumnLimits.requireMaxLength("timeZone", patch.timeZone(), ColumnLimits.TIME_ZONE);
          bean.setTimeZone(patch.timeZone());
      }
      if (patch.entryDisplayCount() != null) {
          bean.setEntryDisplayCount(patch.entryDisplayCount());
      }
      if (patch.active() != null) {
          bean.setActive(patch.active());
      }
      setIfPresent(patch.customDomain(), bean::setCustomDomain);
      setIfPresent(patch.icon(), bean::setIcon);
      setIfPresent(patch.about(), bean::setAbout);
      setIfPresent(patch.analyticsSiteId(), bean::setAnalyticsSiteId);
      setIfPresent(patch.analyticsShareUrl(), bean::setAnalyticsShareUrl);
      setIfPresent(patch.newsletterListUuid(), bean::setNewsletterListUuid);
      setIfPresent(patch.businessId(), bean::setBusinessId);
      setIfPresent(patch.placeType(), bean::setPlaceType);
      setIfPresent(patch.placeLocality(), bean::setPlaceLocality);
      setIfPresent(patch.placeRegion(), bean::setPlaceRegion);
      setIfPresent(patch.placeCountry(), bean::setPlaceCountry);
      if (patch.placeLat() != null) {
          bean.setPlaceLat(twoDecimals("placeLat", patch.placeLat()));
      }
      if (patch.placeLng() != null) {
          bean.setPlaceLng(twoDecimals("placeLng", patch.placeLng()));
      }
      setIfPresent(patch.bookingUrl(), bean::setBookingUrl);
      for (String name : cleared) {
          clearOnBean(bean, name);
      }
      Set<String> touched = new HashSet<>(ClearFields.setFields(patch));
      touched.addAll(cleared);
      return touched;
  }

  private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
      if (value != null) {
          setter.accept(value);
      }
  }

  /** The column is numeric(5,2)/(6,2): store what the database would keep, as the form's value would be. */
  private static String twoDecimals(String field, Double value) {
      if (!Double.isFinite(value)) {
          throw ApiException.badRequest(field + " must be a finite number.");
      }
      return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static void clearOnBean(WeblogConfigBean bean, String name) {
      switch (name) {
          case "tagline" -> bean.setTagline(null);
          case "customDomain" -> bean.setCustomDomain(null);
          case "icon" -> bean.setIcon(null);
          case "about" -> bean.setAbout(null);
          case "analyticsSiteId" -> bean.setAnalyticsSiteId(null);
          case "analyticsShareUrl" -> bean.setAnalyticsShareUrl(null);
          case "newsletterListUuid" -> bean.setNewsletterListUuid(null);
          case "businessId" -> bean.setBusinessId(null);
          case "placeType" -> bean.setPlaceType(null);
          case "placeLocality" -> bean.setPlaceLocality(null);
          case "placeRegion" -> bean.setPlaceRegion(null);
          case "placeCountry" -> bean.setPlaceCountry(null);
          case "placeLat" -> bean.setPlaceLat(null);
          case "placeLng" -> bean.setPlaceLng(null);
          case "bookingUrl" -> bean.setBookingUrl(null);
          default -> throw new IllegalArgumentException("Not clearable: " + name);
      }
  }
  ```
  Check `WeblogConfigBean.setActive`'s parameter type and adapt. If the bean's icon setter is named differently, use the real one.

- [ ] **Step 8: Rewrite `WeblogsApi.update`**

  ```java
      @PatchMapping("/{handle}")
      public AdminDtos.WeblogView update(HttpServletRequest request, @RequestBody AdminDtos.WeblogPatch body)
              throws WebloggerException {
          Weblog weblog = requireActionWeblog(request);
          Set<String> cleared = ClearFields.validate(body.clear(), body,
                  AdminDtos.WEBLOG_CLEARABLE, AdminDtos.WEBLOG_PAIRS);

          // The settings form's own bean carries the change, so the form's copyTo stores it:
          // one way to store each value, whichever surface changed it.
          WeblogConfigBean bean = new WeblogConfigBean();
          bean.copyFrom(weblog);
          Set<String> touched = AdminDtos.applyPatch(bean, body, cleared);

          List<WeblogSettingsRules.Violation> broken = WeblogSettingsRules.check(bean,
                  WeblogSettingsRules.Context.current(weblog.getHandle(), weblogger)).stream()
                  .filter(v -> v.involvesAny(touched))
                  .toList();
          if (!broken.isEmpty()) {
              String detail = broken.stream().map(WeblogSettingsRules.Violation::detail)
                      .collect(Collectors.joining(" "));
              throw broken.stream().allMatch(WeblogSettingsRules.Violation::conflict)
                      ? ApiException.conflict(detail) : ApiException.badRequest(detail);
          }

          bean.copyTo(weblog, weblogger.getBusinessManager());
          weblogger.getWeblogManager().saveWeblog(weblog);
          weblogger.flush();
          // WeblogPageCache expires only against weblog.lastModified; invalidate bumps it.
          CacheManager.invalidate(weblog);
          return AdminDtos.toView(weblog);
      }
  ```
  The previous inline custom-domain and entry-count checks are gone; `WeblogsApiCustomDomainTest` must still pass with its exact messages and statuses. Update the class javadoc where it described the inline checks.

  In `McpToolCatalog`, change the `weblogs update` action description to `"Change a weblog's settings; name fields in clear to remove them."`.

- [ ] **Step 9: Run the API tests**

  Run: `mvn -q -o -pl app test -Dtest='WeblogsApi*Test,ClearFieldsTest,Mcp*Test,OpenApiDocumentTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS. `McpDocsTest` is unaffected, because no action names changed.

- [ ] **Step 10: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/ClearFields.java \
          app/src/test/java/org/apache/roller/weblogger/ui/restapi/dto/ClearFieldsTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApi.java \
          app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApiTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java
  git commit -m "feat(api): the weblog settings the form has, and a clear list, through PATCH /weblogs"
  ```
  Also add any other test file you had to touch for the record shapes.

---

### Task 3: Shared weblog creation rules, adopted by the Create Weblog page

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogCreationRules.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogCreationRulesTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/CreateWeblogController.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/CreateWeblogControllerTest.java`
- Modify: `app/src/main/resources/ApplicationResources.properties`

**Interfaces:**
- Consumes: `CreateWeblogBean` (`handle`, `name`, `description` = tagline, `emailAddress`, `locale`, `timeZone`, `theme`), `UserManager.getWeblogPermissions(User)`, `WeblogManager.getWeblogByHandle/addWeblog`, `ThemeManager.getEnabledThemesList()`, the `Weblog(handle, creator, name, description, email, theme, locale, timeZone)` constructor, and `websiteSettings.locale.invalid`/`websiteSettings.timeZone.invalid` (Task 1).
- Produces (Task 4 relies on these):
  ```java
  public final class WeblogCreationRules {
      public static final int MAX_TEXT = 255;
      /** One refusal; status is the API's (400, 403 or 409); field is the form field id, null for the page. */
      public record Violation(String field, String messageKey, String detail, int status) { }
      public record Context(boolean creationAllowed, boolean groupBlogging, String allowedChars,
                            Set<String> reservedPaths, List<String> themeIds,
                            UserManager users, WeblogManager weblogs) {
          public static Context current(Weblogger weblogger);
      }
      /** Null when this user may create a weblog, else the 403 refusal. A lookup that fails throws. */
      public static Violation mayCreate(User user, Context context) throws WebloggerException;
      /** Every broken field rule, handle first. A lookup that fails throws. */
      public static List<Violation> check(CreateWeblogBean bean, Context context) throws WebloggerException;
      /** Creates the weblog exactly as the page always has: addWeblog with the user as creator. */
      public static Weblog create(CreateWeblogBean bean, User user, WeblogManager weblogs) throws WebloggerException;
  }
  ```

- [ ] **Step 1: Characterisation tests for the page**

  `CreateWeblogControllerTest` already covers the handle rules. Add a test with javadoc `Characterisation: pins the page before the rules move.`, if one is missing, for each of:
  - creation turned off: `execute` returns `.GenericError` with `createWebsite.disabled`;
  - group blogging off with an existing membership: `.GenericError` with `createWebsite.oneBlogLimit`;
  - a handle with a space: field error on `handle`;
  - a taken handle: field error on `handle`, and `bean.getHandle()` is reset to null.

  Run: `mvn -q -o -pl app test -Dtest='CreateWeblogControllerTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS.

- [ ] **Step 2: Write the failing `WeblogCreationRulesTest`**

  ```java
  package org.apache.roller.weblogger.ui.controllers;

  import java.util.List;
  import java.util.Set;
  import org.apache.roller.weblogger.business.UserManager;
  import org.apache.roller.weblogger.business.WeblogManager;
  import org.apache.roller.weblogger.pojos.User;
  import org.apache.roller.weblogger.pojos.Weblog;
  import org.apache.roller.weblogger.pojos.WeblogPermission;
  import org.apache.roller.weblogger.ui.controllers.core.CreateWeblogBean;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.*;
  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.when;

  class WeblogCreationRulesTest {

      private UserManager users;
      private WeblogManager weblogs;

      @BeforeEach
      void setUp() {
          users = mock(UserManager.class);
          weblogs = mock(WeblogManager.class);
      }

      private WeblogCreationRules.Context context(boolean allowed, boolean groupBlogging) {
          return new WeblogCreationRules.Context(allowed, groupBlogging, "A-Za-z0-9",
                  Set.of("api", "page", "search", "roller-ui", "mine"), List.of("journal", "travel"), users, weblogs);
      }

      private static CreateWeblogBean bean(String handle) {
          CreateWeblogBean bean = new CreateWeblogBean();
          bean.setHandle(handle);
          bean.setName("A weblog");
          bean.setTheme("journal");
          bean.setLocale("en_US");
          bean.setTimeZone("Europe/Berlin");
          return bean;
      }

      private static User user() {
          User user = new User();
          user.setUserName("jake");
          return user;
      }

      private List<String> keys(CreateWeblogBean bean) throws Exception {
          return WeblogCreationRules.check(bean, context(true, true)).stream()
                  .map(WeblogCreationRules.Violation::messageKey).toList();
      }

      @Test
      void aGoodRequestBreaksNoRule() throws Exception {
          assertEquals(List.of(), keys(bean("travels")));
      }

      @Test
      void creationTurnedOffIsA403() throws Exception {
          WeblogCreationRules.Violation v = WeblogCreationRules.mayCreate(user(), context(false, true));
          assertEquals(403, v.status());
          assertEquals("Weblog creation is turned off on this site.", v.detail());
      }

      @Test
      void withoutGroupBloggingAMemberMayNotCreateASecond() throws Exception {
          when(users.getWeblogPermissions(org.mockito.ArgumentMatchers.any(User.class)))
                  .thenReturn(List.of(new WeblogPermission()));
          WeblogCreationRules.Violation v = WeblogCreationRules.mayCreate(user(), context(true, false));
          assertEquals("This site allows one weblog per user.", v.detail());
          assertNull(WeblogCreationRules.mayCreate(user(), context(true, true)));
      }

      @Test
      void reservedPathsAreRefusedIgnoringCase() throws Exception {
          for (String handle : List.of("api", "API", "Search", "page", "mine")) {
              assertEquals(List.of("createWeblog.error.reservedHandle"), keys(bean(handle)), handle);
          }
      }

      @Test
      void aBlankOrIllegalOrLongHandleIsRefused() throws Exception {
          assertEquals(List.of("createWeblog.error.invalidHandle"), keys(bean(" ")));
          assertEquals(List.of("createWeblog.error.invalidHandle"), keys(bean("my blog")));
          assertEquals(List.of("createWeblog.error.invalidHandle"), keys(bean("a".repeat(256))));
      }

      @Test
      void aTakenHandleIsAConflict() throws Exception {
          when(weblogs.getWeblogByHandle("travels")).thenReturn(new Weblog());
          WeblogCreationRules.Violation v = WeblogCreationRules.check(bean("travels"), context(true, true)).get(0);
          assertEquals(409, v.status());
          assertEquals("handle", v.field());
      }

      @Test
      void anUnknownOrSitewideThemeIsRefusedWithTheValidIds() throws Exception {
          CreateWeblogBean frontpage = bean("travels");
          frontpage.setTheme("frontpage");
          WeblogCreationRules.Violation v = WeblogCreationRules.check(frontpage, context(true, true)).get(0);
          assertEquals("theme", v.field());
          assertEquals("theme must be one of: journal, travel.", v.detail());
          CreateWeblogBean none = bean("travels");
          none.setTheme(null);
          assertEquals(List.of("createWeblog.error.invalidTheme"), keys(none));
      }

      @Test
      void aMissingNameAndAnUnknownZoneAreRefused() throws Exception {
          CreateWeblogBean bean = bean("travels");
          bean.setName("  ");
          bean.setTimeZone("Mars/Olympus_Mons");
          assertEquals(List.of("createWeblog.error.nameRequired", "websiteSettings.timeZone.invalid"), keys(bean));
      }
  }
  ```

- [ ] **Step 3: Run it to see it fail**

  Run: `mvn -q -o -pl app test -Dtest='WeblogCreationRulesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: compilation failure.

- [ ] **Step 4: Implement `WeblogCreationRules`**

  ```java
  package org.apache.roller.weblogger.ui.controllers;

  import java.util.ArrayList;
  import java.util.Arrays;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Locale;
  import java.util.Set;
  import java.util.stream.Stream;
  import org.apache.commons.lang3.CharSetUtils;
  import org.apache.commons.lang3.StringUtils;
  import org.apache.roller.weblogger.WebloggerException;
  import org.apache.roller.weblogger.business.UserManager;
  import org.apache.roller.weblogger.business.WeblogManager;
  import org.apache.roller.weblogger.business.Weblogger;
  import org.apache.roller.weblogger.config.WebloggerConfig;
  import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
  import org.apache.roller.weblogger.pojos.User;
  import org.apache.roller.weblogger.pojos.Weblog;
  import org.apache.roller.weblogger.ui.controllers.core.CreateWeblogBean;
  import org.apache.roller.weblogger.ui.controllers.util.UIUtils;

  /**
   * Who may create a weblog, and what a new weblog's handle, name, theme,
   * locale and zone must be: one home for the Create Weblog page and
   * {@code POST /api/v1/weblogs}. Creation only: a weblog that already exists
   * under a handle now reserved keeps working.
   */
  public final class WeblogCreationRules {

      /** handle, name and tagline are varchar(255). */
      public static final int MAX_TEXT = 255;
      /** The $site-wide aggregator theme; it breaks any weblog that adopts it. */
      private static final String SITEWIDE_ONLY_THEME_ID = "frontpage";

      public record Violation(String field, String messageKey, String detail, int status) { }

      public record Context(boolean creationAllowed, boolean groupBlogging, String allowedChars,
                            Set<String> reservedPaths, List<String> themeIds,
                            UserManager users, WeblogManager weblogs) {
          public Context {
              reservedPaths = Set.copyOf(reservedPaths);
              themeIds = List.copyOf(themeIds);
          }

          public static Context current(Weblogger weblogger) {
              String allowed = WebloggerConfig.getProperty("username.allowedChars");
              Set<String> reserved = new LinkedHashSet<>();
              Stream.of("rendering.weblogMapper.rollerProtectedUrls", "rendering.weblogMapper.userProtectedUrls")
                      .map(WebloggerConfig::getProperty)
                      .filter(StringUtils::isNotBlank)
                      .flatMap(list -> Arrays.stream(list.split(",")))
                      .map(s -> s.trim().toLowerCase(Locale.ROOT))
                      .filter(s -> !s.isEmpty())
                      .forEach(reserved::add);
              List<String> themes = weblogger.getThemeManager().getEnabledThemesList().stream()
                      .map(t -> t.getId())
                      .filter(id -> !SITEWIDE_ONLY_THEME_ID.equals(id))
                      .toList();
              return new Context(
                      WebloggerRuntimeConfig.getBooleanProperty("site.allowUserWeblogCreation"),
                      WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"),
                      StringUtils.isBlank(allowed) ? UIUtils.DEFAULT_ALLOWED_CHARS : allowed,
                      reserved, themes, weblogger.getUserManager(), weblogger.getWeblogManager());
          }
      }

      private WeblogCreationRules() {
      }

      public static Violation mayCreate(User user, Context context) throws WebloggerException {
          if (!context.creationAllowed()) {
              return new Violation(null, "createWebsite.disabled", "Weblog creation is turned off on this site.", 403);
          }
          if (!context.groupBlogging() && !context.users().getWeblogPermissions(user).isEmpty()) {
              return new Violation(null, "createWebsite.oneBlogLimit", "This site allows one weblog per user.", 403);
          }
          return null;
      }

      public static List<Violation> check(CreateWeblogBean bean, Context context) throws WebloggerException {
          List<Violation> found = new ArrayList<>();
          checkHandle(bean.getHandle(), context, found);
          if (StringUtils.isBlank(bean.getName())) {
              found.add(new Violation("name", "createWeblog.error.nameRequired", "name is required.", 400));
          } else if (bean.getName().trim().length() > MAX_TEXT) {
              found.add(new Violation("name", "createWeblog.error.tooLong",
                      "'name' must be " + MAX_TEXT + " characters or fewer.", 400));
          }
          if (bean.getDescription() != null && bean.getDescription().length() > MAX_TEXT) {
              found.add(new Violation("description", "createWeblog.error.tooLong",
                      "'tagline' must be " + MAX_TEXT + " characters or fewer.", 400));
          }
          if (bean.getTheme() == null || !context.themeIds().contains(bean.getTheme())) {
              found.add(new Violation("theme", "createWeblog.error.invalidTheme",
                      "theme must be one of: " + String.join(", ", context.themeIds()) + ".", 400));
          }
          String locale = StringUtils.trimToNull(bean.getLocale());
          if (locale != null && UIUtils.getLocales().stream().map(Locale::toString).noneMatch(locale::equals)) {
              found.add(new Violation("locale", "websiteSettings.locale.invalid",
                      "locale must be one the settings form offers, for example en_US.", 400));
          }
          String zone = StringUtils.trimToNull(bean.getTimeZone());
          if (zone != null && !UIUtils.getTimeZones().contains(zone)) {
              found.add(new Violation("timeZone", "websiteSettings.timeZone.invalid",
                      "timeZone must be a time zone id, for example Europe/Berlin.", 400));
          }
          return found;
      }

      private static void checkHandle(String handle, Context context, List<Violation> found)
              throws WebloggerException {
          if (StringUtils.isBlank(handle)) {
              found.add(new Violation("handle", "createWeblog.error.invalidHandle", "handle is required.", 400));
          } else if (!CharSetUtils.keep(handle, context.allowedChars()).equals(handle)
                  || handle.length() > MAX_TEXT) {
              found.add(new Violation("handle", "createWeblog.error.invalidHandle",
                      "handle may use only " + context.allowedChars() + ", at most " + MAX_TEXT + " characters.",
                      400));
          } else if (context.reservedPaths().contains(handle.toLowerCase(Locale.ROOT))) {
              found.add(new Violation("handle", "createWeblog.error.reservedHandle",
                      "handle \"" + handle + "\" is reserved for the site's own pages.", 400));
          } else if (context.weblogs().getWeblogByHandle(handle) != null) {
              found.add(new Violation("handle", "createWeblog.error.handleExists",
                      "A weblog with that handle already exists.", 409));
          }
      }

      public static Weblog create(CreateWeblogBean bean, User user, WeblogManager weblogs)
              throws WebloggerException {
          Weblog weblog = new Weblog(bean.getHandle(), user.getUserName(), bean.getName().trim(),
                  bean.getDescription(), bean.getEmailAddress(), bean.getTheme(),
                  bean.getLocale(), bean.getTimeZone());
          weblogs.addWeblog(weblog);
          return weblog;
      }
  }
  ```
  If `getEnabledThemesList()` returns a type whose id accessor is not `getId()`, use the real accessor. Add the new keys:
  ```properties
  createWeblog.error.reservedHandle=That handle is reserved for pages of the site itself.
  createWeblog.error.invalidTheme=Choose one of the themes listed.
  createWeblog.error.nameRequired=A weblog needs a name.
  createWeblog.error.tooLong=Use 255 characters or fewer.
  ```

- [ ] **Step 5: Run the rules test**

  Expected: PASS.

- [ ] **Step 6: Point `CreateWeblogController` at the rules**

  - **`execute`:** replace the creation and one-blog checks with:
    ```java
    WeblogCreationRules.Violation refusal;
    try {
        refusal = WeblogCreationRules.mayCreate(user, WeblogCreationRules.Context.current(weblogger));
    } catch (WebloggerException ex) {
        log.error("error checking whether this user may create a weblog", ex);
        addError(model, "generic.error.check.logs", request);
        return ".GenericError";
    }
    if (refusal != null) {
        addError(model, refusal.messageKey(), request);
        return ".GenericError";
    }
    ```
    Keep the pre-fill lines after it.
  - **`save`:** do the same with flash errors and `redirect:/roller-ui/menu.rol`. **This fixes a fail-open:** today a failed membership lookup in `save` is logged and the save goes ahead. Now it fails closed, with `addFlashError(redirectAttributes, "generic.error.check.logs", request)` and the redirect. Add a test for it.
  - **`myValidate`:** call the rules and mark each violation:
    ```java
    private void myValidate(HttpServletRequest request, Model model, CreateWeblogBean bean) {
        try {
            for (WeblogCreationRules.Violation v : WeblogCreationRules.check(bean,
                    WeblogCreationRules.Context.current(weblogger))) {
                addFieldError(model, v.field(), v.messageKey(), request);
                if ("createWeblog.error.handleExists".equals(v.messageKey())) {
                    bean.setHandle(null);
                }
            }
        } catch (WebloggerException ex) {
            // A check that could not run is not one that passed.
            log.error("error checking the new weblog", ex);
            addError(model, "generic.error.check.logs", request);
        }
    }
    ```
  - **Create through the shared call:** replace the `new Weblog(...)` and `addWeblog` lines with `WeblogCreationRules.create(bean, user, weblogger.getWeblogManager());`.

  Add page tests:
  - `api` as a handle is a field error on `handle` with `createWeblog.error.reservedHandle`, and `addWeblog` is never called.
  - theme `frontpage` is a field error on `theme`.
  - a membership lookup that throws in `save` creates nothing.

  These need the same config fixture the file uses (`ControllerTestFixture.setConfigProperty`), and `rendering.weblogMapper.rollerProtectedUrls` must be readable. If it is not in the test config, set it with `setConfigProperty`.

- [ ] **Step 7: Run the page and rules tests**

  Run: `mvn -q -o -pl app test -Dtest='CreateWeblogControllerTest,WeblogCreationRulesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS.

- [ ] **Step 8: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogCreationRules.java \
          app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogCreationRulesTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/CreateWeblogController.java \
          app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/CreateWeblogControllerTest.java \
          app/src/main/resources/ApplicationResources.properties
  git commit -m "fix(create-weblog): refuse reserved handles and unknown themes; one home for the creation rules"
  ```

---

### Task 4: `POST /v1/weblogs` and MCP `weblogs create`

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogCreateApi.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogCreateApiTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java` (`WeblogCreate` record and `toBean`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java`, and `McpToolCatalogTest` if it pins the weblogs actions
- Modify: `docs/api/README.md` (the Site administration section and the MCP table)

**Interfaces:**
- Consumes (Task 3): `WeblogCreationRules.mayCreate/check/create`, `Context.current`. From `BaseApiController`: `requireAuthenticatedUser(request)`, `weblogger`. From Task 2: `AdminDtos.toView(Weblog)`.
- Produces:
  ```java
  public record WeblogCreate(String handle, String name, String theme, String tagline,
                             String emailAddress, String locale, String timeZone) { }
  public static CreateWeblogBean toBean(WeblogCreate body, User user);  // omitted email/locale/zone = the user's
  ```

- [ ] **Step 1: Write the failing `WeblogCreateApiTest`**

  Use the standalone MockMvc style of `WeblogsApiTest`: `new WeblogCreateApi()`, set its `weblogger` field to a Mockito mock with mocked `WeblogManager`, `UserManager` and `ThemeManager`, and `ApiExceptionHandler` as advice. Put the user in the request as the attribute the API's `requireAuthenticatedUser` reads (see `BaseApiController` and `EntriesApiWriteTest`). Stub the static config with `mockStatic(WebloggerRuntimeConfig.class)`:
  - `getBooleanProperty("site.allowUserWeblogCreation")` returns true;
  - `getBooleanProperty("groupblogging.enabled")` returns true;

  and with `mockStatic(WebloggerConfig.class)`:
  - `getProperty("username.allowedChars")` returns `"A-Za-z0-9"`;
  - `getProperty("rendering.weblogMapper.rollerProtectedUrls")` returns `"api,page,search"`;
  - `getProperty("rendering.weblogMapper.userProtectedUrls")` returns `""`.

  The theme manager returns two mocked themes, `journal` and `frontpage`. Tests:
  ```java
  @Test
  void createsTheWeblogWithTheUserAsCreatorAndAnswers201() throws Exception {
      // POST /v1/weblogs {"handle":"travels","name":"Travels","theme":"journal","timeZone":"Europe/Berlin"}
      // expect 201; Location ends with /v1/weblogs/travels; JSON handle "travels";
      // ArgumentCaptor<Weblog> on weblogManager.addWeblog: handle travels, creatorUserName "jake",
      // theme "journal", timeZone "Europe/Berlin", locale and emailAddress copied from the user
  }

  @Test
  void aReservedHandleIsA400() throws Exception {
      // {"handle":"API","name":"x","theme":"journal"} -> 400, detail contains "reserved"; addWeblog never called
  }

  @Test
  void aTakenHandleIsA409() throws Exception { /* getWeblogByHandle("travels") returns a weblog -> 409 */ }

  @Test
  void theSitewideThemeIsRefusedWithTheValidIds() throws Exception {
      // theme "frontpage" -> 400 detail "theme must be one of: journal."
  }

  @Test
  void missingFieldsAreA400NamingThem() throws Exception {
      // {} -> 400; detail contains "handle is required." and "name is required." and "theme must be one of"
  }

  @Test
  void creationTurnedOffIsA403() throws Exception {
      // site.allowUserWeblogCreation false -> 403 "Weblog creation is turned off on this site."
  }
  ```
  Write each out in full. A 400 with several violations joins their details with a space (Step 3).

- [ ] **Step 2: Run it to see it fail**

  Run: `mvn -q -o -pl app test -Dtest='WeblogCreateApiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: compilation failure.

- [ ] **Step 3: Implement**

  In `AdminDtos`:
  ```java
  /** The body of POST /weblogs. handle, name and theme are required; the rest default to the user's own. */
  public record WeblogCreate(
          @Schema(description = "The weblog's handle: its URL path. Letters and digits.") String handle,
          @Schema(description = "The weblog's name.") String name,
          @Schema(description = "An enabled theme id, for example journal; a wrong one answers with the list.")
          String theme,
          String tagline, String emailAddress,
          @Schema(description = "A locale such as en_US; omitted, the user's own.") String locale,
          @Schema(description = "A time zone id such as Europe/Berlin; omitted, the user's own.") String timeZone) {
  }

  /** The create form's bean, filled as the page pre-fills it: omitted email, locale and zone are the user's. */
  public static CreateWeblogBean toBean(WeblogCreate body, User user) {
      CreateWeblogBean bean = new CreateWeblogBean();
      bean.setHandle(body.handle() == null ? null : body.handle().trim());
      bean.setName(body.name());
      bean.setTheme(body.theme());
      bean.setDescription(body.tagline());
      bean.setEmailAddress(body.emailAddress() != null ? body.emailAddress() : user.getEmailAddress());
      bean.setLocale(body.locale() != null ? body.locale() : user.getLocale());
      bean.setTimeZone(body.timeZone() != null ? body.timeZone() : user.getTimeZone());
      return bean;
  }
  ```
  `emailAddress` keeps the column check `PATCH` uses: `ColumnLimits.requireMaxLength("emailAddress", ..., ColumnLimits.WEBLOG_EMAIL_ADDRESS)` in the controller before `check`.

  ```java
  package org.apache.roller.weblogger.ui.restapi.v1;

  /**
   * POST /v1/weblogs: create a weblog, by the same rules as the Create Weblog
   * page (WeblogCreationRules). Not {@code @AdminScoped}, unlike WeblogsApi:
   * any user the page lets create a weblog may do it with a writing token. A
   * token limited to one weblog meets ApiScopeInterceptor's default (no weblog
   * on this route, so 404), and a READ token its 403.
   */
  @RestController
  @RequestMapping("/v1/weblogs")
  public class WeblogCreateApi extends BaseApiController implements UISecurityEnforced {

      @PostMapping("")
      public ResponseEntity<AdminDtos.WeblogView> create(HttpServletRequest request,
              @RequestBody AdminDtos.WeblogCreate body) throws WebloggerException {
          User user = requireAuthenticatedUser(request);
          WeblogCreationRules.Context context = WeblogCreationRules.Context.current(weblogger);
          WeblogCreationRules.Violation refusal = WeblogCreationRules.mayCreate(user, context);
          if (refusal != null) {
              throw ApiException.forbidden(refusal.detail());
          }
          ColumnLimits.requireMaxLength("emailAddress", body.emailAddress(), ColumnLimits.WEBLOG_EMAIL_ADDRESS);
          CreateWeblogBean bean = AdminDtos.toBean(body, user);
          List<WeblogCreationRules.Violation> broken = WeblogCreationRules.check(bean, context);
          if (!broken.isEmpty()) {
              String detail = broken.stream().map(WeblogCreationRules.Violation::detail)
                      .collect(Collectors.joining(" "));
              throw broken.stream().allMatch(v -> v.status() == 409)
                      ? ApiException.conflict(detail) : ApiException.badRequest(detail);
          }
          Weblog weblog = WeblogCreationRules.create(bean, user, weblogger.getWeblogManager());
          weblogger.flush();
          URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                  .path("/{handle}").buildAndExpand(weblog.getHandle()).toUri();
          return ResponseEntity.created(location).body(AdminDtos.toView(weblog));
      }

      @Override
      public boolean isUserRequired() {
          return true;
      }

      @Override
      public boolean isWeblogRequired() {
          return false;
      }

      @Override
      public List<String> requiredWeblogPermissionActions() {
          return List.of();
      }

      @Override
      public List<String> requiredGlobalPermissionActions() {
          return List.of(GlobalPermission.LOGIN);
      }
  }
  ```

- [ ] **Step 4: Run it; then watch the coverage test fail**

  Run: `mvn -q -o -pl app test -Dtest='WeblogCreateApiTest,McpCoverageTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: `WeblogCreateApiTest` PASSES. `McpCoverageTest` FAILS, naming `POST /v1/weblogs` as having neither an action nor an exclusion.

- [ ] **Step 5: Declare the action and document it**

  In `McpToolCatalog`, the `weblogs` tool becomes:
  ```java
  tool("weblogs", "Weblogs: create one (any writing token not limited to a weblog); list them and change "
          + "their settings (ADMIN tokens).", false,
          action("list", "List weblogs.", "GET /v1/weblogs"),
          action("get", "One weblog's settings.", "GET " + W),
          action("create", "Create a weblog; you become its owner.", "POST /v1/weblogs"),
          action("update", "Change a weblog's settings; name fields in clear to remove them.", "PATCH " + W)),
  ```
  In `docs/api/README.md`:
  - **MCP table:** the `weblogs` row lists `list`, `get`, `create`, `update`. Match the order `McpDocsTest` expects; read it.
  - **Site administration route block:** add `POST  /api/v1/weblogs`.
  - **A short paragraph:**
    - who may create (the Create Weblog page's rule; not limited to one weblog; `POST` or `ADMIN` role);
    - which fields are required;
    - that reserved handles and `frontpage` are refused and a wrong theme lists the valid ones;
    - that the creator becomes the owner.
  - **The thirteen new `PATCH` fields and `clear`:** document them in the same section, one line each, with the rules from the spec's settings table.

- [ ] **Step 6: Run the MCP and API tests**

  Run: `mvn -q -o -pl app test -Dtest='Mcp*Test,WeblogCreateApiTest,WeblogsApi*Test,OpenApiDocumentTest,ApiRouteTableTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS. If `ApiRouteTableTest` or `McpToolCatalogTest` pins route or action counts, update the counts.

- [ ] **Step 7: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogCreateApi.java \
          app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogCreateApiTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java \
          docs/api/README.md
  git commit -m "feat(api): create a weblog through POST /weblogs and MCP weblogs create"
  ```
  Also add any MCP test file whose pinned counts you updated.

---

### Task 5: Event dates, in the weblog's zone, in the editor and the API

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRules.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRulesTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryBean.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/EntryBeanTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryEditController.java` (beside `resolvePubTime`, lines ~146 and ~564)
- Modify: the `EntryEditController` test class that covers saves (find it with `grep -l resolvePubTime\|pubTimeInvalid app/src/test -r`)
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp` (lines ~448 and ~455)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiWriteTest.java`
- Modify: `app/src/main/resources/ApplicationResources.properties`

**Interfaces:**
- Produces (Task 6 relies on these):
  ```java
  // EntryFieldRules
  public static Timestamp parseEventTime(String wallClock, TimeZone zone); // blank -> null; garbage throws DateTimeParseException
  public static String formatWallClock(Timestamp instant, TimeZone zone);  // "yyyy-MM-dd'T'HH:mm"; "" for null
  public static boolean endsBeforeStart(Timestamp start, Timestamp end);  // both set and end < start
  // EntryWrite gains, after geoLongitude: String eventStart, String eventEnd, String eventLocation
  // EntryView gains, after geoLongitude: Instant eventStart, Instant eventEnd, String eventLocation
  // EntryDtos.applyWrite ends by refusing an end before the start (400 "eventEnd must not be before eventStart.")
  ```

- [ ] **Step 1: Write the failing rules tests**

  In `EntryFieldRulesTest`:
  ```java
  private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

  @Test
  void anEventTimeIsWallClockInTheWeblogsZone() {
      assertEquals(Instant.parse("2026-08-02T17:30:00Z"),
              EntryFieldRules.parseEventTime("2026-08-02T19:30", BERLIN).toInstant());
      assertEquals(Instant.parse("2026-08-02T17:30:00Z"),
              EntryFieldRules.parseEventTime("2026-08-02T19:30:00", BERLIN).toInstant(), "seconds are accepted");
      assertNull(EntryFieldRules.parseEventTime("  ", BERLIN));
      assertThrows(DateTimeParseException.class, () -> EntryFieldRules.parseEventTime("2026-08-02T19:30+02:00", BERLIN));
  }

  @Test
  void formatWallClockIsTheInverseToTheMinute() {
      Timestamp start = Timestamp.from(Instant.parse("2026-08-02T17:30:00Z"));
      assertEquals("2026-08-02T19:30", EntryFieldRules.formatWallClock(start, BERLIN));
      assertEquals("", EntryFieldRules.formatWallClock(null, BERLIN));
  }

  @Test
  void endsBeforeStartOnlyWhenBothAreSet() {
      Timestamp a = Timestamp.from(Instant.parse("2026-08-02T17:30:00Z"));
      Timestamp b = Timestamp.from(Instant.parse("2026-08-02T20:00:00Z"));
      assertTrue(EntryFieldRules.endsBeforeStart(b, a));
      assertFalse(EntryFieldRules.endsBeforeStart(a, b));
      assertFalse(EntryFieldRules.endsBeforeStart(a, a));
      assertFalse(EntryFieldRules.endsBeforeStart(null, a));
      assertFalse(EntryFieldRules.endsBeforeStart(a, null));
  }
  ```

- [ ] **Step 2: Run them to see them fail; then implement**

  Run: `mvn -q -o -pl app test -Dtest='EntryFieldRulesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` (expected: compilation failure). Then add to `EntryFieldRules`, and extend the class javadoc's rule list with "An event time is wall-clock time in the WEBLOG's zone, like a pubtime":

  ```java
  private static final DateTimeFormatter WALL_CLOCK =
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

  /** An event's start or end: wall-clock time in the weblog's zone, the format a pubtime takes. */
  public static Timestamp parseEventTime(String wallClock, TimeZone zone) {
      return parsePubTime(wallClock, zone);
  }

  /** {@code instant} as wall-clock time in {@code zone}, to the minute; "" for null. */
  public static String formatWallClock(Timestamp instant, TimeZone zone) {
      if (instant == null) {
          return "";
      }
      return instant.toInstant().atZone(zone.toZoneId()).toLocalDateTime().format(WALL_CLOCK);
  }

  public static boolean endsBeforeStart(Timestamp start, Timestamp end) {
      return start != null && end != null && end.before(start);
  }
  ```
  Run again: PASS.

- [ ] **Step 3: Move the editor to the weblog's zone (tests first)**

  In `EntryBeanTest`, replace any test pinning the old server-zone parse or format with:
  ```java
  @Test
  void eventTimesShowInTheWeblogsZone() {
      // an entry whose weblog is Europe/Berlin and whose eventStart is the instant 2026-08-02T17:30:00Z:
      // after bean.copyFrom(entry, Locale.ROOT), getEventStartLocal() is "2026-08-02T19:30"
  }

  @Test
  void theSubmittedTextIsKeptUntilTheControllerResolvesIt() {
      // bean.setEventStartLocal("not a time"); getEventStartLocal() returns "not a time" (no silent clearing)
  }
  ```
  Build the entry and weblog the way `EntryBeanTest` already does. In the save test class, add:
  - a save with `eventStartLocal` `2026-08-02T19:30` on a Berlin weblog stores the instant `2026-08-02T17:30:00Z`;
  - `eventEndLocal` before `eventStartLocal` is a field error on `entry_bean_eventEndLocal` with `entryEdit.eventEndBeforeStart`, and nothing is saved;
  - unparseable `eventStartLocal` is a field error on `entry_bean_eventStartLocal` with `entryEdit.eventTimeInvalid`.

  Run them and see them fail.

  Then change `EntryBean`:
  - keep the `Timestamp eventStart`/`eventEnd` fields and their getters and setters (`copyTo` reads them);
  - add `String eventStartLocal`/`eventEndLocal` fields;
  - `getEventStartLocal()`/`getEventEndLocal()` return them;
  - `setEventStartLocal(String)`/`setEventEndLocal(String)` only store the text;
  - delete `formatLocalInput`, `parseLocalInput` and the server-zone javadoc;
  - in `copyFrom`, set:
    ```java
    TimeZone zone = entry.getWebsite().getTimeZoneInstance();
    this.eventStartLocal = EntryFieldRules.formatWallClock(entry.getEventStart(), zone);
    this.eventEndLocal = EntryFieldRules.formatWallClock(entry.getEventEnd(), zone);
    ```

  In `EntryEditController`, add and call it right after each `resolvePubTime(...)` call:
  ```java
  /**
   * The event times the form submitted, read in the action weblog's zone as a
   * pubtime is, and set on the bean for copyTo. A value that will not parse,
   * or an end before the start, is a field error; doSave's hasErrors gate
   * then blocks the save.
   */
  private void resolveEventTimes(EntryBean bean, HttpServletRequest request, Model model) {
      TimeZone zone = getActionWeblog(request).getTimeZoneInstance();
      Timestamp start = eventTime(bean.getEventStartLocal(), zone, "entry_bean_eventStartLocal", request, model);
      Timestamp end = eventTime(bean.getEventEndLocal(), zone, "entry_bean_eventEndLocal", request, model);
      if (EntryFieldRules.endsBeforeStart(start, end)) {
          addFieldError(model, "entry_bean_eventEndLocal", "entryEdit.eventEndBeforeStart", request);
      }
      bean.setEventStart(start);
      bean.setEventEnd(end);
  }

  private Timestamp eventTime(String text, TimeZone zone, String fieldId, HttpServletRequest request, Model model) {
      try {
          return EntryFieldRules.parseEventTime(text, zone);
      } catch (DateTimeParseException e) {
          addFieldError(model, fieldId, "entryEdit.eventTimeInvalid", request);
          return null;
      }
  }
  ```
  In `EntryEdit.jsp`, the two inputs now echo submitted text, so escape them: `value="${fn:escapeXml(bean.eventStartLocal)}"` and `value="${fn:escapeXml(bean.eventEndLocal)}"`. Check the `fn` taglib is declared in that JSP; an undeclared prefix renders as literal text. Add the keys:
  ```properties
  entryEdit.eventTimeInvalid=Event date/time is invalid.
  entryEdit.eventEndBeforeStart=The event cannot end before it starts.
  ```
  Run: `mvn -q -o -pl app test -Dtest='EntryBeanTest,EntryEdit*Test,EditorJspEscapingTest,EntryFieldRulesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`. Expected: PASS.

- [ ] **Step 4: The API (tests first)**

  In `EntriesApiWriteTest`, add, using a weblog whose time zone is `Europe/Berlin`:
  ```java
  @Test
  void anEventIsWrittenInTheWeblogsZoneAndReadBackAsInstants() throws Exception {
      // create {"title":"t","text":"x","jsonLdType":"EVENT","eventStart":"2026-08-02T19:30",
      //         "eventEnd":"2026-08-02T22:00","eventLocation":"Waldbühne"}
      // expect 201; saved entry eventStart instant 2026-08-02T17:30:00Z, eventEnd 20:00:00Z, location "Waldbühne";
      // JSON eventStart "2026-08-02T17:30:00Z"
  }

  @Test
  void anEventTimeWithAnOffsetIsA400ShowingTheFormat() throws Exception {
      // "eventStart":"2026-08-02T19:30+02:00" -> 400, detail
      // "eventStart must be a wall-clock time in the weblog's zone, for example 2026-08-02T19:30."
  }

  @Test
  void anEndBeforeTheStartIsA400EvenWhenOnlyOneIsSent() throws Exception {
      // update of an entry whose stored eventStart is 2026-08-02T17:30:00Z with {"eventEnd":"2026-08-02T18:00"}
      // (16:00Z) -> 400 "eventEnd must not be before eventStart."; saveWeblogEntry never called
  }

  @Test
  void aBlankEventTimeIsRefusedNotTreatedAsClearing() throws Exception {
      // {"eventStart":" "} -> 400 with the format detail
  }

  @Test
  void anEventLocationOver255IsA400() throws Exception { /* "eventLocation" of 256 chars -> 400 */ }
  ```
  Run and see them fail. Then in `EntryDtos`:
  - **`EntryWrite`:** add `String eventStart, String eventEnd, String eventLocation` after `geoLongitude`, each with `@Schema`:
    - `eventStart`: "With EVENT: the start, wall-clock time in the weblog's zone, for example 2026-08-02T19:30."
    - `eventEnd`: "The end, in the same form; not before eventStart."
    - `eventLocation`: "The venue's name."
  - **`EntryView`:** add `Instant eventStart, Instant eventEnd, String eventLocation` after `geoLongitude`, filled with `instant(entry.getEventStart())`, `instant(entry.getEventEnd())` and `entry.getEventLocation()`.
  - **`applyWrite`**, after the geo fields:
    ```java
    if (write.eventStart() != null) {
        entry.setEventStart(eventTime("eventStart", write.eventStart(), weblog));
    }
    if (write.eventEnd() != null) {
        entry.setEventEnd(eventTime("eventEnd", write.eventEnd(), weblog));
    }
    if (write.eventLocation() != null) {
        ColumnLimits.requireMaxLength("eventLocation", write.eventLocation(), 255);
        entry.setEventLocation(write.eventLocation());
    }
    if (EntryFieldRules.endsBeforeStart(entry.getEventStart(), entry.getEventEnd())) {
        throw ApiException.badRequest("eventEnd must not be before eventStart.");
    }
    ```
    and:
    ```java
    /** A blank value is refused, not read as "remove": removing is clear's job. */
    private static Timestamp eventTime(String field, String raw, Weblog weblog) {
        String detail = field + " must be a wall-clock time in the weblog's zone, for example 2026-08-02T19:30.";
        if (raw.isBlank()) {
            throw ApiException.badRequest(detail);
        }
        try {
            return EntryFieldRules.parseEventTime(raw, weblog.getTimeZoneInstance());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(detail, e);
        }
    }
    ```
    If `ColumnLimits` has (or should have) a named constant for `event_location`, add `EVENT_LOCATION = 255` there with the column comment, and use it.

  Every `new EntryWrite(...)`/`new EntryView(...)` call in tests gains the three arguments.

- [ ] **Step 5: Run the API, MCP and OpenAPI tests**

  Run: `mvn -q -o -pl app test -Dtest='Entries*Test,EntryDtos*Test,Mcp*Test,ToolSchemasTest,OpenApiDocumentTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS. The `entries` tool schema gains the three arguments with no MCP code change.

- [ ] **Step 6: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add -A app/src/main/java/org/apache/roller/weblogger/ui/controllers \
             app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java \
             app/src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp \
             app/src/main/resources/ApplicationResources.properties \
             app/src/test/java/org/apache/roller/weblogger
  git commit -m "feat(entries): event dates through the API; the editor reads them in the weblog's zone"
  ```
  Check `git status` first, so only this task's files are staged.

---

### Task 6: `clear` on entries and media

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/MediaDtos.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java` (`update`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiWriteTest.java`, `MediaApiTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/EntryBeanTest.java`

**Interfaces:**
- Consumes: `ClearFields.validate(List<String>, Record, List<String>, List<List<String>>)` (Task 2); the event fields (Task 5).
- Produces:
  - `EntryWrite` gains a final component `List<String> clear`.
  - `EntryDtos.ENTRY_CLEARABLE` and `ENTRY_PAIRS = List.of(List.of("geoLatitude", "geoLongitude"))`.
  - `applyWrite(WeblogEntry, EntryWrite, Weblog, Set<String> cleared)` (the 3-argument form is removed).
  - `MediaPatch` gains a final `List<String> clear`.
  - `MediaDtos.MEDIA_CLEARABLE = List.of("altText", "focalX", "focalY")` and `MEDIA_PAIRS = List.of(List.of("focalX", "focalY"))`.
  - `applyPatch(MediaFile, MediaPatch, Set<String> cleared)`.

- [ ] **Step 1: Write the failing tests**

  In `EntriesApiWriteTest`:
  ```java
  @Test
  void clearRemovesEachClearableEntryField() throws Exception {
      // an entry with summary, metaTitle, searchDescription, canonicalUrl, featuredImageId, ogImageId,
      // jsonLdType EVENT, geo pair, eventStart, eventEnd, eventLocation all set;
      // PATCH {"clear":[every ENTRY_CLEARABLE name]} -> 200;
      // each is null on the entry and null-or-missing in JSON, except jsonLdType == "BLOG_POSTING"
  }

  @Test
  void clearOnCreateIsA400() throws Exception {
      // POST {"title":"t","text":"x","clear":["summary"]} -> 400 "clear applies only to updates."
  }

  @Test
  void clearRefusalsChangeNothing() throws Exception {
      // {"clear":["title"]} -> 400; {"clear":["geoLatitude"]} -> 400 "geoLatitude and geoLongitude are cleared together.";
      // {"summary":"s","clear":["summary"]} -> 400; saveWeblogEntry never called
  }

  @Test
  void clearingTheStartLeavesAnEndAlone() throws Exception {
      // stored start and end; {"clear":["eventStart"]} -> 200, start null, end unchanged
  }
  ```
  In `MediaApiTest`:
  ```java
  @Test
  void clearRemovesAltTextAndTheFocalPair() throws Exception {
      // file with altText "a", focalX 0.2, focalY 0.8; PATCH {"clear":["altText","focalX","focalY"]} -> 200; all null
  }

  @Test
  void halfTheFocalPairIsA400() throws Exception { /* {"clear":["focalX"]} -> 400 */ }
  ```
  In `EntryBeanTest`:
  ```java
  @Test
  void aClearedEntryShowsEmptyFieldsInTheEditor() {
      // copyFrom an entry with null featuredImageId, geo, eventStart, eventLocation:
      // the bean's getters give null or "" for each, with no exception
  }
  ```
  Run: `mvn -q -o -pl app test -Dtest='EntriesApiWriteTest,MediaApiTest,EntryBeanTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: FAIL or compilation failure.

- [ ] **Step 2: Implement entries**

  In `EntryDtos`:
  ```java
  public static final List<String> ENTRY_CLEARABLE = List.of("summary", "metaTitle", "searchDescription",
          "canonicalUrl", "featuredImageId", "ogImageId", "jsonLdType", "geoLatitude", "geoLongitude",
          "eventStart", "eventEnd", "eventLocation");
  public static final List<List<String>> ENTRY_PAIRS = List.of(List.of("geoLatitude", "geoLongitude"));
  ```
  - **`EntryWrite`:** add the last component with `@Schema(description = "On update, fields to remove: summary, metaTitle, searchDescription, canonicalUrl, featuredImageId, ogImageId, jsonLdType (back to BLOG_POSTING), geoLatitude with geoLongitude, eventStart, eventEnd, eventLocation. Null never removes anything.") List<String> clear`.
  - **`applyWrite(entry, write, weblog, cleared)`:** apply the clears after the sets and before the event-order check:
    ```java
    for (String name : cleared) {
        switch (name) {
            case "summary" -> entry.setSummary(null);
            case "metaTitle" -> entry.setMetaTitle(null);
            case "searchDescription" -> entry.setSearchDescription(null);
            case "canonicalUrl" -> entry.setCanonicalUrl(null);
            case "featuredImageId" -> entry.setFeaturedImageId(null);
            case "ogImageId" -> entry.setOgImageId(null);
            case "jsonLdType" -> entry.setJsonLdType(JsonLdType.BLOG_POSTING);
            case "geoLatitude" -> entry.setGeoLatitude(null);
            case "geoLongitude" -> entry.setGeoLongitude(null);
            case "eventStart" -> entry.setEventStart(null);
            case "eventEnd" -> entry.setEventEnd(null);
            case "eventLocation" -> entry.setEventLocation(null);
            default -> throw new IllegalArgumentException("Not clearable: " + name);
        }
    }
    ```

  In `EntriesWriteApi`:
  - **`create`:** at the top, `if (body.clear() != null && !body.clear().isEmpty()) { throw ApiException.badRequest("clear applies only to updates."); }`, then `applyWrite(entry, body, weblog, Set.of())`.
  - **`update`:** after `requireLiveEntry`, `Set<String> cleared = ClearFields.validate(body.clear(), body, EntryDtos.ENTRY_CLEARABLE, EntryDtos.ENTRY_PAIRS);`, then `applyWrite(entry, body, weblog, cleared)`.
  - **Previews:** they call `applyWrite`. Pass `Set.of()`; if their body has a non-empty `clear`, refuse it with the same create message.

- [ ] **Step 3: Implement media**

  - **`MediaDtos`:** add the constants. `MediaPatch` gains `@Schema(description = "Fields to remove: altText, focalX with focalY.") List<String> clear` as its last component. `applyPatch(file, patch, cleared)` applies the sets as today, then: `altText` → `file.setAltText(null)`; `focalX`/`focalY` → `setFocalX(null)`/`setFocalY(null)`.
  - **`MediaApi.update`:** call `ClearFields.validate(body.clear(), body, MediaDtos.MEDIA_CLEARABLE, MediaDtos.MEDIA_PAIRS)` before the directory lookup, and pass the result to `applyPatch`. Update the `MediaPatch` javadoc: an empty string still sets an empty `altText`, and `clear` removes it.

- [ ] **Step 4: Run the tests**

  Run: `mvn -q -o -pl app test -Dtest='Entries*Test,Media*Test,EntryBeanTest,ClearFieldsTest,Mcp*Test,ToolSchemasTest,OpenApiDocumentTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS.

- [ ] **Step 5: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/src/main/java/org/apache/roller/weblogger/ui/restapi \
          app/src/test/java/org/apache/roller/weblogger/ui/restapi \
          app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/EntryBeanTest.java
  git commit -m "feat(api): clear removes values from entries and media"
  ```

---

### Task 7: `RemoteImageFetcher`, which refuses every internal address

**Files:**
- Modify: `app/pom.xml` (one dependency)
- Create: `app/src/main/java/org/apache/roller/weblogger/business/fetch/PublicAddresses.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/fetch/ImageKind.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/fetch/FetchRefusal.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/fetch/FetchedImage.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/fetch/RemoteImageFetcher.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/fetch/package-info.java` (one-line javadoc, like the other packages)
- Create: `app/src/test/java/org/apache/roller/weblogger/business/fetch/PublicAddressesTest.java`, `ImageKindTest.java`, `RemoteImageFetcherTest.java`

**Interfaces:**
- Produces (Task 8 relies on these):
  ```java
  public final class PublicAddresses { public static boolean isPublic(InetAddress address); }
  public enum ImageKind { JPEG, PNG, GIF, WEBP;
      public String extension(); public String contentType();
      public static Optional<ImageKind> sniff(byte[] head); }
  public final class FetchRefusal extends Exception { public FetchRefusal(String detail); }  // getMessage() is the detail
  public record FetchedImage(String fileName, ImageKind kind, byte[] bytes) { public long size(); }
  @Component
  public class RemoteImageFetcher implements AutoCloseable {
      public RemoteImageFetcher();                                   // the production policy, always
      RemoteImageFetcher(Policy policy);                             // package-private: tests only
      public FetchedImage fetch(String url, long maxBytes, String preferredName) throws FetchRefusal;
      public void close();
  }
  ```

- [ ] **Step 1: Add the dependency**

  In `app/pom.xml`, beside the other runtime dependencies:
  ```xml
  <!-- Image import by URL (business.fetch): the one common Java client with a
       per-client DnsResolver, so the fetcher can refuse internal addresses and
       connect only to addresses it checked. Version from Spring Boot's BOM. -->
  <dependency>
      <groupId>org.apache.httpcomponents.client5</groupId>
      <artifactId>httpclient5</artifactId>
  </dependency>
  ```
  Run: `mvn -q -o -pl app dependency:tree -Dincludes=org.apache.httpcomponents.client5,org.apache.httpcomponents.core5`. Expected: `httpclient5:5.6.4` with its `httpcore5` artifacts. If offline resolution fails, run once without `-o`.

- [ ] **Step 2: Write the failing `PublicAddressesTest`**

  ```java
  package org.apache.roller.weblogger.business.fetch;

  import java.net.InetAddress;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.ValueSource;

  import static org.junit.jupiter.api.Assertions.assertFalse;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  /** Every refused range at its edges, and public neighbours just outside them. */
  class PublicAddressesTest {

      @ParameterizedTest
      @ValueSource(strings = {
              "0.0.0.0", "0.255.255.255", "10.0.0.0", "10.255.255.255", "100.64.0.0", "100.127.255.255",
              "127.0.0.1", "127.255.255.255", "169.254.0.0", "169.254.169.254", "169.254.255.255",
              "172.16.0.0", "172.31.255.255", "192.0.0.0", "192.0.0.255", "192.0.2.0", "192.0.2.255",
              "192.168.0.0", "192.168.255.255", "198.18.0.0", "198.19.255.255", "198.51.100.7", "203.0.113.9",
              "224.0.0.1", "239.255.255.255", "240.0.0.0", "255.255.255.255",
              "::", "::1", "::127.0.0.1", "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe80::1",
              "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "ff02::1", "2001:db8::1", "2001:0:4136:e378::1",
              "2002:7f00:1::", "100::1", "::ffff:127.0.0.1", "::ffff:10.1.2.3", "64:ff9b::a9fe:a9fe"})
      void refused(String literal) throws Exception {
          assertFalse(PublicAddresses.isPublic(InetAddress.getByName(literal)), literal);
      }

      @ParameterizedTest
      @ValueSource(strings = {
              "1.1.1.1", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
              "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.0",
              "192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255",
              "2606:4700:4700::1111", "2001:4860:4860::8888", "fec0::1", "::ffff:8.8.8.8", "64:ff9b::808:808"})
      void allowed(String literal) throws Exception {
          assertTrue(PublicAddresses.isPublic(InetAddress.getByName(literal)), literal);
      }
  }
  ```
  `fec0::/10` (deprecated site-local) is outside the spec's list and is allowed here. Java's `InetAddress.getByName("::ffff:127.0.0.1")` returns an `Inet4Address`. Both refusals hold either way.

- [ ] **Step 3: Implement `PublicAddresses`**

  ```java
  package org.apache.roller.weblogger.business.fetch;

  import java.net.InetAddress;
  import java.net.UnknownHostException;
  import java.util.Arrays;
  import java.util.List;

  /**
   * Whether an address is on the public internet. Everything else (loopback,
   * private, CGNAT and Tailscale, link-local and cloud metadata, Docker
   * networks, documentation, benchmarking, multicast, reserved) is refused. An
   * IPv4-mapped or NAT64 IPv6 address is judged by the IPv4 address inside
   * it. Beyond the spec's list, IPv4-compatible (::/96), discard-only
   * (100::/64), Teredo (2001::/32) and 6to4 (2002::/16) are refused too: each
   * can carry an internal IPv4 address, and no image host needs them.
   */
  public final class PublicAddresses {

      private static final List<Cidr> REFUSED_V4 = Cidr.all("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10",
              "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
              "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4",
              "240.0.0.0/4");
      private static final List<Cidr> REFUSED_V6 = Cidr.all("::/96", "::1/128", "fc00::/7", "fe80::/10",
              "ff00::/8", "2001:db8::/32", "100::/64", "2001::/32", "2002::/16");
      private static final Cidr MAPPED = Cidr.of("::ffff:0:0/96");
      private static final Cidr NAT64 = Cidr.of("64:ff9b::/96");

      private PublicAddresses() {
      }

      public static boolean isPublic(InetAddress address) {
          byte[] bytes = address.getAddress();
          if (bytes.length == 4) {
              return REFUSED_V4.stream().noneMatch(c -> c.contains(bytes));
          }
          if (MAPPED.contains(bytes) || NAT64.contains(bytes)) {
              return REFUSED_V4.stream().noneMatch(c -> c.contains(Arrays.copyOfRange(bytes, 12, 16)));
          }
          return REFUSED_V6.stream().noneMatch(c -> c.contains(bytes));
      }

      /** An address block: the first {@code bits} bits of {@code prefix}. */
      private record Cidr(byte[] prefix, int bits) {

          static List<Cidr> all(String... blocks) {
              return Arrays.stream(blocks).map(Cidr::of).toList();
          }

          static Cidr of(String block) {
              int slash = block.indexOf('/');
              try {
                  // Literals only: getByName does no lookup for an address literal.
                  byte[] prefix = InetAddress.getByName(block.substring(0, slash)).getAddress();
                  return new Cidr(prefix, Integer.parseInt(block.substring(slash + 1)));
              } catch (UnknownHostException e) {
                  throw new IllegalArgumentException(block, e);
              }
          }

          boolean contains(byte[] address) {
              if (address.length != prefix.length) {
                  return false;
              }
              int whole = bits / 8;
              for (int i = 0; i < whole; i++) {
                  if (address[i] != prefix[i]) {
                      return false;
                  }
              }
              int rest = bits % 8;
              if (rest == 0) {
                  return true;
              }
              int mask = 0xFF << (8 - rest) & 0xFF;
              return (address[whole] & mask) == (prefix[whole] & mask);
          }
      }
  }
  ```
  `InetAddress.getByName("::ffff:0:0")` yields an `Inet4Address` (0.0.0.0), which would make `MAPPED` a 4-byte block. Build `MAPPED` from explicit bytes instead (16 bytes: ten zeros, then `0xFF, 0xFF`, then four zeros, with `bits` 96), and keep the test row `::ffff:10.1.2.3` (which Java also parses to an `Inet4Address`). The mapped branch then covers mapped addresses that arrive as 16 bytes from a resolver. Run `PublicAddressesTest`: PASS.

- [ ] **Step 4: `ImageKind`, test first**

  ```java
  class ImageKindTest {
      @Test
      void sniffsByMagicBytes() {
          assertEquals(Optional.of(ImageKind.JPEG), ImageKind.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
          assertEquals(Optional.of(ImageKind.PNG), ImageKind.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
          assertEquals(Optional.of(ImageKind.GIF), ImageKind.sniff("GIF89a....".getBytes(StandardCharsets.US_ASCII)));
          assertEquals(Optional.of(ImageKind.GIF), ImageKind.sniff("GIF87a....".getBytes(StandardCharsets.US_ASCII)));
          assertEquals(Optional.of(ImageKind.WEBP), ImageKind.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
      }

      @Test
      void refusesEverythingElse() {
          assertEquals(Optional.empty(), ImageKind.sniff(new byte[0]));
          assertEquals(Optional.empty(), ImageKind.sniff("<svg xmlns=".getBytes(StandardCharsets.US_ASCII)));
          assertEquals(Optional.empty(), ImageKind.sniff("<!doctype html>".getBytes(StandardCharsets.US_ASCII)));
          assertEquals(Optional.empty(), ImageKind.sniff("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII)));
      }

      @Test
      void namesItsExtensionAndType() {
          assertEquals("jpg", ImageKind.JPEG.extension());
          assertEquals("image/webp", ImageKind.WEBP.contentType());
      }
  }
  ```
  ```java
  package org.apache.roller.weblogger.business.fetch;

  import java.nio.charset.StandardCharsets;
  import java.util.Arrays;
  import java.util.Optional;

  /** The raster images an import accepts, recognised by their first bytes, never by a header or a URL. */
  public enum ImageKind {
      JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), GIF("gif", "image/gif"), WEBP("webp", "image/webp");

      private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

      private final String extension;
      private final String contentType;

      ImageKind(String extension, String contentType) {
          this.extension = extension;
          this.contentType = contentType;
      }

      public String extension() {
          return extension;
      }

      public String contentType() {
          return contentType;
      }

      public static Optional<ImageKind> sniff(byte[] head) {
          if (startsWith(head, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
              return Optional.of(JPEG);
          }
          if (startsWith(head, PNG_MAGIC)) {
              return Optional.of(PNG);
          }
          if (startsWith(head, ascii("GIF87a")) || startsWith(head, ascii("GIF89a"))) {
              return Optional.of(GIF);
          }
          if (head.length >= 12 && startsWith(head, ascii("RIFF"))
                  && Arrays.equals(Arrays.copyOfRange(head, 8, 12), ascii("WEBP"))) {
              return Optional.of(WEBP);
          }
          return Optional.empty();
      }

      private static boolean startsWith(byte[] head, byte[] magic) {
          return head.length >= magic.length && Arrays.equals(Arrays.copyOf(head, magic.length), magic);
      }

      private static byte[] ascii(String s) {
          return s.getBytes(StandardCharsets.US_ASCII);
      }
  }
  ```

- [ ] **Step 5: Write the failing `RemoteImageFetcherTest`**

  The test serves bytes from a JDK `HttpServer` on `127.0.0.1:0` and constructs the fetcher with a permissive policy. It also covers the production policy's refusals, which need no server.

  ```java
  package org.apache.roller.weblogger.business.fetch;

  import com.sun.net.httpserver.HttpServer;
  import java.io.OutputStream;
  import java.net.InetAddress;
  import java.net.InetSocketAddress;
  import java.time.Duration;
  import java.util.concurrent.atomic.AtomicInteger;
  import org.apache.hc.client5.http.DnsResolver;
  import org.junit.jupiter.api.AfterEach;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.ValueSource;

  import static org.junit.jupiter.api.Assertions.*;

  class RemoteImageFetcherTest {

      private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
      private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9};

      private HttpServer server;
      private RemoteImageFetcher fetcher;
      private final AtomicInteger hits = new AtomicInteger();

      @BeforeEach
      void start() throws Exception {
          server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
          server.start();
          fetcher = new RemoteImageFetcher(new RemoteImageFetcher.Policy(
                  a -> true, true, Duration.ofSeconds(2), Duration.ofSeconds(2), 3));
      }

      @AfterEach
      void stop() {
          server.stop(0);
          fetcher.close();
      }

      private String url(String path) {
          return "http://127.0.0.1:" + server.getAddress().getPort() + path;
      }

      private void serve(String path, int status, String contentType, byte[] body) {
          server.createContext(path, exchange -> {
              hits.incrementAndGet();
              exchange.getResponseHeaders().add("Content-Type", contentType);
              exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
              try (OutputStream out = exchange.getResponseBody()) {
                  out.write(body);
              }
          });
      }

      private void redirect(String path, String location) {
          server.createContext(path, exchange -> {
              exchange.getResponseHeaders().add("Location", location);
              exchange.sendResponseHeaders(302, -1);
              exchange.close();
          });
      }

      private String refusal(RemoteImageFetcher f, String url, long max) {
          return assertThrows(FetchRefusal.class, () -> f.fetch(url, max, null)).getMessage();
      }

      @Test
      void fetchesAJpegNamedFromTheUrlWithTheDetectedExtension() throws Exception {
          serve("/photos/sunset", 200, "image/jpeg", JPEG);
          FetchedImage image = fetcher.fetch(url("/photos/sunset?w=800"), 1024, null);
          assertEquals("sunset.jpg", image.fileName());
          assertEquals(ImageKind.JPEG, image.kind());
          assertArrayEquals(JPEG, image.bytes());
      }

      @Test
      void theTypeComesFromTheBytesNotTheHeaderAndThePreferredNameWins() throws Exception {
          serve("/x.jpg", 200, "text/plain", PNG);
          FetchedImage image = fetcher.fetch(url("/x.jpg"), 1024, "Harbour at dusk.jpeg");
          assertEquals(ImageKind.PNG, image.kind());
          assertEquals("Harbour-at-dusk.png", image.fileName());
      }

      @Test
      void htmlAnSvgAndAnEmptyBodyAreNotImages() {
          serve("/fake.jpg", 200, "image/jpeg", "<!doctype html><p>hi".getBytes());
          serve("/logo.svg", 200, "image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes());
          serve("/empty", 200, "image/png", new byte[0]);
          for (String path : new String[] {"/fake.jpg", "/logo.svg", "/empty"}) {
              assertEquals("Not a JPEG, PNG, GIF or WebP image.", refusal(fetcher, url(path), 1024), path);
          }
      }

      @Test
      void aNon200AnswerIsReported() {
          serve("/gone", 404, "text/plain", "no".getBytes());
          assertEquals("The image server answered 404.", refusal(fetcher, url("/gone"), 1024));
      }

      @Test
      void threeRedirectsAreFollowedAndAFourthIsRefused() throws Exception {
          serve("/final", 200, "image/jpeg", JPEG);
          redirect("/r1", "/final");
          redirect("/r2", "/r1");
          redirect("/r3", "/r2");
          redirect("/r4", "/r3");
          assertEquals(ImageKind.JPEG, fetcher.fetch(url("/r3"), 1024, null).kind());
          assertTrue(refusal(fetcher, url("/r4"), 1024).startsWith("The image server redirected more than 3 times"));
      }

      @Test
      void aRedirectToARefusedAddressIsRefused() {
          RemoteImageFetcher strict = new RemoteImageFetcher(new RemoteImageFetcher.Policy(
                  a -> a.getHostAddress().equals("127.0.0.1"), true, Duration.ofSeconds(2), Duration.ofSeconds(2), 3));
          try {
              redirect("/to-metadata", "http://169.254.169.254/latest/meta-data/");
              assertEquals("That address is not on the public internet.", refusal(strict, url("/to-metadata"), 1024));
          } finally {
              strict.close();
          }
      }

      @Test
      void aBodyOverTheLimitIsRefusedWithOrWithoutAContentLength() {
          serve("/big", 200, "image/jpeg", new byte[2048]);
          server.createContext("/chunked", exchange -> {
              exchange.sendResponseHeaders(200, 0); // chunked: no Content-Length
              try (OutputStream out = exchange.getResponseBody()) {
                  out.write(JPEG);
                  out.write(new byte[4096]);
              }
          });
          assertTrue(refusal(fetcher, url("/big"), 1024).startsWith("Larger than this site's"));
          assertTrue(refusal(fetcher, url("/chunked"), 1024).startsWith("Larger than this site's"));
      }

      @Test
      void aServerThatStopsSendingIsAbandonedAtTheDeadline() {
          server.createContext("/stall", exchange -> {
              exchange.sendResponseHeaders(200, 0);
              OutputStream out = exchange.getResponseBody();
              out.write(JPEG);
              out.flush();
              try {
                  Thread.sleep(10_000);
              } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
              }
              exchange.close();
          });
          RemoteImageFetcher quick = new RemoteImageFetcher(new RemoteImageFetcher.Policy(
                  a -> true, true, Duration.ofSeconds(1), Duration.ofMillis(500), 3));
          try {
              long started = System.nanoTime();
              assertEquals("The image server took too long.", refusal(quick, url("/stall"), 1 << 20));
              assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
          } finally {
              quick.close();
          }
      }

      @ParameterizedTest
      @ValueSource(strings = {"ftp://example.com/a.jpg", "file:///etc/passwd", "http://example.com:8080/a.jpg",
              "http://user:pw@example.com/a.jpg", "not a url", "/relative.jpg"})
      void theProductionPolicyRefusesTheseUrlsBeforeConnecting(String url) {
          RemoteImageFetcher production = new RemoteImageFetcher();
          try {
              assertThrows(FetchRefusal.class, () -> production.fetch(url, 1024, null));
          } finally {
              production.close();
          }
      }

      @ParameterizedTest
      @ValueSource(strings = {"http://127.0.0.1/", "HTTPS://127.0.0.1/", "http://[::1]/", "http://[::ffff:127.0.0.1]/",
              "http://10.0.0.1/", "http://169.254.169.254/", "http://localhost/"})
      void theProductionPolicyRefusesInternalAddresses(String url) {
          RemoteImageFetcher production = new RemoteImageFetcher();
          try {
              assertEquals("That address is not on the public internet.", refusal(production, url, 1024));
          } finally {
              production.close();
          }
          assertEquals(0, hits.get());
      }

      @Test
      void aDecimalIpv4LiteralIsRefusedEitherWay() {
          RemoteImageFetcher production = new RemoteImageFetcher();
          try {
              // Java may read 2130706433 as 127.0.0.1 (not public) or as a hostname (not found): refused either way.
              assertThrows(FetchRefusal.class, () -> production.fetch("http://2130706433/", 1024, null));
          } finally {
              production.close();
          }
      }

      @Test
      void aHostIsRefusedWhenAnyOfItsAddressesIsNotPublic() throws Exception {
          DnsResolver mixed = new DnsResolver() {
              @Override
              public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                  return new InetAddress[] {InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.5")};
              }

              @Override
              public String resolveCanonicalHostname(String host) {
                  return host;
              }
          };
          DnsResolver checking = new RemoteImageFetcher.CheckingResolver(mixed, PublicAddresses::isPublic);
          assertThrows(RemoteImageFetcher.NotPublicException.class, () -> checking.resolve("mixed.example"));
      }
  }
  ```
  Notes for the implementer:
  - **The redirect-to-refused-address test:** `169.254.169.254` is an IP literal, so the per-hop literal check refuses it before any connection.
  - **`http://localhost/` in production:** it resolves to loopback, so the resolver refuses it. It must never reach the test server, which listens on another port anyway.
  - **Content-Length in criterion 15:** the spec also lists "a `Content-Length` under the limit" with a larger body. A conforming client stops reading at `Content-Length`, so that body can never be over the limit. The two cases tested here (an honest `Content-Length` over the limit, and none) are the reachable ones. Note this ruling in the report.
  - **`DnsResolver` in 5.6.4:** check the interface. If it has more abstract methods than these two, implement them in `CheckingResolver` by delegating and checking.

- [ ] **Step 6: Run it to see it fail**

  Run: `mvn -q -o -pl app test -Dtest='RemoteImageFetcherTest,ImageKindTest,PublicAddressesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: compilation failure (no fetcher).

- [ ] **Step 7: Implement `FetchRefusal`, `FetchedImage` and `RemoteImageFetcher`**

  ```java
  /** A fetch that was refused or failed; the message is the detail the API shows the caller. */
  public final class FetchRefusal extends Exception {
      private static final long serialVersionUID = 1L;

      public FetchRefusal(String detail) {
          super(detail);
      }

      public FetchRefusal(String detail, Throwable cause) {
          super(detail, cause);
      }
  }
  ```
  ```java
  /** A fetched, recognised image: the name to store it under, its kind, and its bytes. */
  public record FetchedImage(String fileName, ImageKind kind, byte[] bytes) {
      public long size() {
          return bytes.length;
      }
  }
  ```
  ```java
  package org.apache.roller.weblogger.business.fetch;

  import java.io.ByteArrayOutputStream;
  import java.io.IOException;
  import java.io.InputStream;
  import java.net.InetAddress;
  import java.net.URI;
  import java.net.URISyntaxException;
  import java.net.UnknownHostException;
  import java.time.Duration;
  import java.util.Locale;
  import java.util.Properties;
  import java.util.concurrent.Executors;
  import java.util.concurrent.ScheduledExecutorService;
  import java.util.concurrent.ScheduledFuture;
  import java.util.concurrent.TimeUnit;
  import java.util.function.Predicate;
  import org.apache.hc.client5.http.DnsResolver;
  import org.apache.hc.client5.http.SystemDefaultDnsResolver;
  import org.apache.hc.client5.http.classic.methods.HttpGet;
  import org.apache.hc.client5.http.config.ConnectionConfig;
  import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
  import org.apache.hc.client5.http.impl.classic.HttpClients;
  import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
  import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
  import org.apache.hc.core5.http.ClassicHttpResponse;
  import org.apache.hc.core5.http.Header;
  import org.apache.hc.core5.http.HttpEntity;
  import org.apache.hc.core5.net.InetAddressUtils;
  import org.apache.hc.core5.util.Timeout;
  import org.springframework.stereotype.Component;

  /**
   * Fetches one image from a public URL for {@code media import}. Roller runs
   * inside a home network, so the fetcher must never become a way to reach it:
   * every address it connects to is checked (the client's own DnsResolver
   * refuses a host when ANY of its addresses is not public, and the connection
   * then goes to an address that passed), IP-literal hosts are checked before
   * the request, redirects are followed here and re-checked, and no proxy is
   * used. There is no property, profile or flag that loosens this. Only the
   * package-private constructor takes another {@link Policy}, for tests that
   * need a local server.
   */
  @Component
  public class RemoteImageFetcher implements AutoCloseable {

      /** What a fetch may do. {@link #PRODUCTION} is the only policy outside tests. */
      record Policy(Predicate<InetAddress> addresses, boolean anyPort, Duration connectTimeout,
                    Duration deadline, int maxRedirects) {
          static final Policy PRODUCTION = new Policy(PublicAddresses::isPublic, false,
                  Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
      }

      static final String NOT_PUBLIC = "That address is not on the public internet.";
      static final String NOT_AN_IMAGE = "Not a JPEG, PNG, GIF or WebP image.";
      static final String TOO_SLOW = "The image server took too long.";
      private static final int SNIFF_BYTES = 16;

      private final Policy policy;
      private final CloseableHttpClient client;
      private final ScheduledExecutorService deadlines;

      public RemoteImageFetcher() {
          this(Policy.PRODUCTION);
      }

      RemoteImageFetcher(Policy policy) {
          this.policy = policy;
          PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                  .setDnsResolver(new CheckingResolver(SystemDefaultDnsResolver.INSTANCE, policy.addresses()))
                  .setDefaultConnectionConfig(ConnectionConfig.custom()
                          .setConnectTimeout(Timeout.of(policy.connectTimeout()))
                          .setSocketTimeout(Timeout.of(policy.deadline()))
                          .build())
                  .build();
          // No useSystemProperties(): that is what would pick up a JVM proxy.
          this.client = HttpClients.custom()
                  .setConnectionManager(connections)
                  .disableRedirectHandling()
                  .disableAutomaticRetries()
                  .disableContentCompression()
                  .disableCookieManagement()
                  .disableAuthCaching()
                  .setUserAgent("Roller/" + version())
                  .build();
          this.deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
              Thread t = new Thread(r, "roller-image-fetch-deadline");
              t.setDaemon(true);
              return t;
          });
      }

      public FetchedImage fetch(String rawUrl, long maxBytes, String preferredName) throws FetchRefusal {
          URI original = parse(rawUrl);
          long deadline = System.nanoTime() + policy.deadline().toNanos();
          URI uri = original;
          for (int redirects = 0; ; redirects++) {
              checkUrl(uri);
              HttpGet get = new HttpGet(uri);
              get.setHeader("Accept", "image/jpeg, image/png, image/gif, image/webp");
              long remaining = deadline - System.nanoTime();
              if (remaining <= 0) {
                  throw new FetchRefusal(TOO_SLOW);
              }
              ScheduledFuture<?> alarm = deadlines.schedule(get::cancel, remaining, TimeUnit.NANOSECONDS);
              try (ClassicHttpResponse response = client.executeOpen(null, get, null)) {
                  int status = response.getCode();
                  Header location = response.getFirstHeader("Location");
                  if (status >= 300 && status < 400 && location != null) {
                      if (redirects == policy.maxRedirects()) {
                          throw new FetchRefusal("The image server redirected more than "
                                  + policy.maxRedirects() + " times.");
                      }
                      uri = resolveRedirect(uri, location.getValue());
                      continue;
                  }
                  if (status != 200) {
                      throw new FetchRefusal("The image server answered " + status + ".");
                  }
                  byte[] bytes = readCapped(response.getEntity(), maxBytes);
                  ImageKind kind = ImageKind.sniff(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, SNIFF_BYTES)))
                          .orElseThrow(() -> new FetchRefusal(NOT_AN_IMAGE));
                  return new FetchedImage(fileName(preferredName, original, kind), kind, bytes);
              } catch (NotPublicException e) {
                  throw new FetchRefusal(NOT_PUBLIC, e);
              } catch (UnknownHostException e) {
                  throw new FetchRefusal("That host could not be found.", e);
              } catch (IOException e) {
                  throw new FetchRefusal(get.isCancelled() ? TOO_SLOW : "The image server could not be reached.", e);
              } finally {
                  alarm.cancel(false);
              }
          }
      }

      private static URI parse(String rawUrl) throws FetchRefusal {
          try {
              URI uri = new URI(rawUrl == null ? "" : rawUrl.trim());
              if (!uri.isAbsolute() || uri.getHost() == null) {
                  throw new FetchRefusal("That is not a valid http or https URL.");
              }
              return uri;
          } catch (URISyntaxException e) {
              throw new FetchRefusal("That is not a valid http or https URL.", e);
          }
      }

      /** Scheme, port and user info for every hop; an IP-literal host is judged here, before any request. */
      private void checkUrl(URI uri) throws FetchRefusal {
          String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
          if (!"http".equals(scheme) && !"https".equals(scheme)) {
              throw new FetchRefusal("Only http and https URLs are fetched.");
          }
          if (uri.getRawUserInfo() != null) {
              throw new FetchRefusal("A URL with a user name or password is not fetched.");
          }
          int port = uri.getPort();
          if (!policy.anyPort() && port != -1 && port != 80 && port != 443) {
              throw new FetchRefusal("Only ports 80 and 443 are fetched.");
          }
          String host = uri.getHost();
          String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
          if (InetAddressUtils.isIPv4Address(bare) || InetAddressUtils.isIPv6Address(bare)) {
              try {
                  if (!policy.addresses().test(InetAddress.getByName(bare))) {
                      throw new FetchRefusal(NOT_PUBLIC);
                  }
              } catch (UnknownHostException e) {
                  throw new FetchRefusal(NOT_PUBLIC, e);
              }
          }
      }

      private static URI resolveRedirect(URI from, String location) throws FetchRefusal {
          try {
              return from.resolve(new URI(location.trim()));
          } catch (URISyntaxException | IllegalArgumentException e) {
              throw new FetchRefusal("The image server sent an invalid redirect.", e);
          }
      }

      /** Reads at most maxBytes, refusing at the first byte over, whatever Content-Length claimed. */
      private static byte[] readCapped(HttpEntity entity, long maxBytes) throws IOException, FetchRefusal {
          if (entity == null) {
              return new byte[0];
          }
          String limit = String.format(Locale.ROOT, "%.2f", maxBytes / 1048576.0);
          if (entity.getContentLength() > maxBytes) {
              throw new FetchRefusal("Larger than this site's " + limit + " MB per-file limit.");
          }
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          try (InputStream in = entity.getContent()) {
              byte[] buffer = new byte[8192];
              int read;
              while ((read = in.read(buffer)) != -1) {
                  if (out.size() + (long) read > maxBytes) {
                      throw new FetchRefusal("Larger than this site's " + limit + " MB per-file limit.");
                  }
                  out.write(buffer, 0, read);
              }
          }
          return out.toByteArray();
      }

      /** The caller's name, else the URL's last path segment, else "image"; extension from the bytes. */
      static String fileName(String preferred, URI original, ImageKind kind) {
          String base = preferred;
          if (base == null || base.isBlank()) {
              String path = original.getPath() == null ? "" : original.getPath();
              base = path.substring(path.lastIndexOf('/') + 1);
          }
          int dot = base.lastIndexOf('.');
          if (dot > 0) {
              base = base.substring(0, dot);
          }
          base = base.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
          if (base.length() > 100) {
              base = base.substring(0, 100);
          }
          return (base.isEmpty() ? "image" : base) + "." + kind.extension();
      }

      private static String version() {
          Properties props = new Properties();
          try (InputStream in = RemoteImageFetcher.class.getResourceAsStream("/roller-version.properties")) {
              if (in != null) {
                  props.load(in);
              }
          } catch (IOException e) {
              return "unknown";
          }
          return props.getProperty("ro.version", "unknown");
      }

      @Override
      public void close() {
          deadlines.shutdownNow();
          try {
              client.close();
          } catch (IOException e) {
              // closing on shutdown; nothing to report to
          }
      }

      /** Resolves through the delegate, then refuses the host if ANY of its addresses fails the policy. */
      static final class CheckingResolver implements DnsResolver {
          private final DnsResolver delegate;
          private final Predicate<InetAddress> allowed;

          CheckingResolver(DnsResolver delegate, Predicate<InetAddress> allowed) {
              this.delegate = delegate;
              this.allowed = allowed;
          }

          @Override
          public InetAddress[] resolve(String host) throws UnknownHostException {
              InetAddress[] addresses = delegate.resolve(host);
              for (InetAddress address : addresses) {
                  if (!allowed.test(address)) {
                      throw new NotPublicException(host);
                  }
              }
              return addresses;
          }

          @Override
          public String resolveCanonicalHostname(String host) throws UnknownHostException {
              return delegate.resolveCanonicalHostname(host);
          }
      }

      /** A host refused by the policy. An UnknownHostException, so the client unwinds as for a lookup failure. */
      static final class NotPublicException extends UnknownHostException {
          private static final long serialVersionUID = 1L;

          NotPublicException(String host) {
              super(host);
          }
      }
  }
  ```
  - **SLF4J:** none needed here; the controller logs.
  - **An empty `catch` in `close()`:** PMD may flag it. Prefer `catch (IOException e) { log.debug("Closing the image fetcher", e); }` with a logger, or a site suppression with a reason.
  - **An exception wrapping `NotPublicException`:** if the client wraps it in another `IOException`, walk the cause chain before mapping it to a detail.
  - **Behaviour around the deadline:** if the client applies the socket timeout per read rather than per request, the deadline alarm (`get.cancel()`) is what makes the stalled-server test pass. Keep both.

- [ ] **Step 8: Run the fetcher tests**

  Run: `mvn -q -o -pl app test -Dtest='RemoteImageFetcherTest,ImageKindTest,PublicAddressesTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS. Then check what HttpClient does with an IP literal: does it consult the `DnsResolver`? The literal pre-check makes either answer safe. Record the answer in the report (the spec's Risks section asks for it).

- [ ] **Step 9: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/pom.xml app/src/main/java/org/apache/roller/weblogger/business/fetch \
          app/src/test/java/org/apache/roller/weblogger/business/fetch
  git commit -m "feat(media): a fetcher for public images that refuses every internal address"
  ```

---

### Task 8: `POST /v1/weblogs/{handle}/media/imports` and MCP `media import`

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/MediaUploads.java` (a `Source` type, a `store(Source, …)` overload, `maxFileBytes()`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/MediaUploadsTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/MediaDtos.java` (`MediaImport` record)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApiTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java`
- Modify: `app/src/main/resources/log4j2.xml` (only if `roller.mcp` has its own logger entry there; give `roller.media.import` the same one)
- Modify: `docs/api/README.md` (Media section, MCP table)

**Interfaces:**
- Consumes (Task 7): `RemoteImageFetcher.fetch(String, long, String) throws FetchRefusal`, `FetchedImage.fileName()/kind()/bytes()/size()`, `ImageKind.contentType()`.
- Produces:
  ```java
  // MediaUploads
  public interface Source { String fileName(); String contentType(); long size(); boolean isEmpty();
      InputStream openStream() throws IOException;
      static Source of(MultipartFile upload); static Source of(String fileName, String contentType, byte[] bytes); }
  public static Result store(Source source, Weblog weblog, MediaFileDirectory directory, MediaFileManager mfm,
                             RollerMessages messages) throws WebloggerException;
  public static Result store(MultipartFile upload, ...)   // unchanged signature; delegates
  public static long maxFileBytes();                       // uploads.file.maxsize, as FileContentManagerImpl computes it
  // MediaDtos
  public record MediaImport(String url, String name, String directoryId, String altText) { }
  ```

- [ ] **Step 1: `MediaUploads.Source` (tests first)**

  In `MediaUploadsTest`, add a test that `store(Source.of("a.jpg", "image/jpeg", bytes), ...)` reaches `createMediaFile` with name `a.jpg`, content type `image/jpeg`, length `bytes.length`, and a stream of those bytes. Use the file's existing manager mocks. Also test that an empty `Source.of("a.jpg", "image/jpeg", new byte[0])` is refused with "The file is empty.". Run them and see them fail.

  Then refactor `store` to take a `Source`; `store(MultipartFile, …)` becomes `return store(Source.of(upload), …);`. `effectiveContentType` and `buildMediaFile` take the `Source`. `Source.of(MultipartFile)` adapts `getOriginalFilename`, `getContentType`, `getSize`, `isEmpty` and `getInputStream`. `Source.of(String, String, byte[])` serves a `ByteArrayInputStream`. Add:
  ```java
  /** The per-file limit in bytes, computed exactly as FileContentManagerImpl.canSave computes it. */
  public static long maxFileBytes() {
      BigDecimal maxFileMB = new BigDecimal(WebloggerRuntimeConfig.getProperty("uploads.file.maxsize"));
      return (long) (RollerConstants.ONE_MB_IN_BYTES * maxFileMB.doubleValue());
  }
  ```
  Run: `mvn -q -o -pl app test -Dtest='MediaUploadsTest,MediaApiTest,MediaUploadTest,MediaFileAddControllerTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`. Expected: PASS (uploads unchanged).

- [ ] **Step 2: Write the failing import tests in `MediaApiTest`**

  The controller gets the fetcher through a package-visible field: `@Autowired RemoteImageFetcher imageFetcher;`. Tests set it to `mock(RemoteImageFetcher.class)`. Stub `WebloggerRuntimeConfig` with `mockStatic`: `getBooleanProperty("uploads.enabled")` returns true, and `getProperty("uploads.file.maxsize")` returns `"2.00"`.
  ```java
  @Test
  void anImportStoresTheFetchedImageAndAnswers201() throws Exception {
      // fetcher.fetch("https://img.example.com/a/sunset.jpg?sig=SECRET", 2097152, null)
      //   returns new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES)
      // POST /v1/weblogs/myblog/media/imports {"url":"https://img.example.com/a/sunset.jpg?sig=SECRET","altText":"Dusk"}
      // expect 201; Location ends with /api/v1/weblogs/myblog/media/<id>; JSON name "sunset.jpg"; altText "Dusk";
      // createMediaFile received contentType image/jpeg and length JPEG_BYTES.length
  }

  @Test
  void aRefusedFetchIsA400CarryingItsDetail() throws Exception {
      // fetcher throws new FetchRefusal("That address is not on the public internet.") -> 400 with that detail
  }

  @Test
  void withUploadsOffTheImportIsRefusedBeforeAnyFetch() throws Exception {
      // uploads.enabled false -> 400 "File upload is disabled for this site."; verify(fetcher, never()).fetch(...)
  }

  @Test
  void aForbiddenExtensionCarriesTheUploadDetail() throws Exception {
      // createMediaFile adds error "error.upload.forbiddenFile" to the RollerMessages -> 400 "Files of this type may not be uploaded."
  }

  @Test
  void aMissingUrlIsA400() throws Exception { /* {} -> 400 "url is required." */ }

  @Test
  void eachImportLogsOneLineWithTheHostAndNeverThePathOrQuery() throws Exception {
      // capture logger "roller.media.import" the way McpToolExecutorTest captures "roller.mcp";
      // after one successful and one refused import: exactly two INFO lines, one per import, each containing
      // "weblog=myblog", "host=img.example.com", "outcome=", "bytes=", "ms=", and none containing "sunset" or "SECRET"
  }
  ```
  Run them and see them fail.

- [ ] **Step 3: Implement the route**

  `MediaDtos`:
  ```java
  /** The body of POST /media/imports: an image to fetch from a public URL. */
  public record MediaImport(
          @Schema(description = "A public http or https URL of a JPEG, PNG, GIF or WebP image.") String url,
          @Schema(description = "File name to store it under; the extension follows the image's real type.")
          String name,
          @Schema(description = "Directory id; omitted, the weblog's default directory.") String directoryId,
          @Schema(description = "Alt text for the image.") String altText) {
  }
  ```
  `MediaApi`:
  ```java
      private static final Logger IMPORT_LOG = LoggerFactory.getLogger("roller.media.import");

      @Autowired
      RemoteImageFetcher imageFetcher;

      /**
       * Import an image from a public URL. The fetched bytes are stored through
       * MediaUploads.store, the path a browser upload takes, so every upload rule
       * applies. One INFO line per import on roller.media.import: weblog, host,
       * outcome, bytes, ms. Never the path or query, where a signed URL keeps
       * its credentials.
       */
      @PostMapping("/imports")
      public ResponseEntity<MediaDtos.MediaView> importImage(HttpServletRequest request,
              @RequestBody MediaDtos.MediaImport body) throws WebloggerException {
          Weblog weblog = requireActionWeblog(request);
          if (body.url() == null || body.url().isBlank()) {
              throw ApiException.badRequest("url is required.");
          }
          ColumnLimits.requireMaxLength("altText", body.altText(), ColumnLimits.MEDIA_ALT_TEXT);
          MediaFileManager mfm = weblogger.getMediaFileManager();
          MediaFileDirectory directory = StringUtils.isNotBlank(body.directoryId())
                  ? requireDirectory(request, body.directoryId())
                  : MediaUploads.defaultDirectory(weblog, mfm);
          if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
              throw ApiException.badRequest("File upload is disabled for this site.");
          }

          long started = System.nanoTime();
          String outcome = "error";
          long bytes = 0;
          try {
              FetchedImage image = imageFetcher.fetch(body.url(), MediaUploads.maxFileBytes(), body.name());
              bytes = image.size();
              RollerMessages messages = new RollerMessages();
              MediaUploads.Result stored = MediaUploads.store(
                      MediaUploads.Source.of(image.fileName(), image.kind().contentType(), image.bytes()),
                      weblog, directory, mfm, messages);
              if (stored.file() == null) {
                  outcome = stored.status();
                  throw ApiException.badRequest(stored.detail());
              }
              MediaFile file = stored.file();
              if (body.altText() != null) {
                  file.setAltText(body.altText());
                  mfm.updateMediaFile(weblog, file);
              }
              weblogger.flush();
              outcome = "created";
              URI location = ServletUriComponentsBuilder.fromContextPath(request)
                      .path("/api/v1/weblogs/{handle}/media/{id}")
                      .buildAndExpand(weblog.getHandle(), file.getId()).toUri();
              return ResponseEntity.created(location).body(MediaDtos.toView(file, url(weblog, file)));
          } catch (FetchRefusal e) {
              outcome = "refused";
              throw ApiException.badRequest(e.getMessage(), e);
          } finally {
              IMPORT_LOG.info("weblog={} host={} outcome={} bytes={} ms={}", weblog.getHandle(), hostOf(body.url()),
                      outcome, bytes, (System.nanoTime() - started) / 1_000_000);
          }
      }

      /** The URL's host only, lower-cased; "?" when it has none. URI parsing keeps it to hostname characters. */
      private static String hostOf(String url) {
          try {
              String host = new URI(url.trim()).getHost();
              return host == null ? "?" : host.toLowerCase(Locale.ROOT);
          } catch (URISyntaxException e) {
              return "?";
          }
      }
  ```
  Use `MediaUploads.store`'s actual parameter order. If `ColumnLimits.requireMaxLength` does not accept null, guard it. The logging test needs `/api/v1/...` in the Location; MockMvc's context path is empty, which `fromContextPath` handles.

- [ ] **Step 4: Declare the action; update docs**

  In `McpToolCatalog`, the `media` tool:
  - description: `"A weblog's media files and folders. Add an image with import (a public URL); uploading files is REST-only."`;
  - add `action("import", "Import an image from a public http(s) URL; use its id as an entry's featuredImageId.", "POST " + W + "/media/imports")` after `get`;
  - the `POST " + W + "/media"` exclusion's reason becomes `"Multipart file upload; a tool call carries JSON. Use media import to add an image by URL."`.

  In `docs/api/README.md`:
  - **MCP table:** the `media` row gains `import`.
  - **Media route block:** add `POST   /api/v1/weblogs/{handle}/media/imports   import an image from a URL`.
  - **A paragraph on the import:**
    - the body;
    - JPEG/PNG/GIF/WebP by content, SVG refused;
    - public hosts only, ports 80/443, at most 3 redirects, 30 s;
    - the per-file limit and uploads-enabled rules it shares with uploads;
    - every refusal is a 400 with a detail.

  If `log4j2.xml` routes `roller.mcp` explicitly, add a matching `roller.media.import` logger entry.

- [ ] **Step 5: Run the tests**

  Run: `mvn -q -o -pl app test -Dtest='Media*Test,Mcp*Test,ToolSchemasTest,OpenApiDocumentTest,ApiRouteTableTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS. Coverage and docs tests included.

- [ ] **Step 6: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/MediaUploads.java \
          app/src/test/java/org/apache/roller/weblogger/ui/controllers/MediaUploadsTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/MediaDtos.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java \
          app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApiTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java \
          docs/api/README.md
  git commit -m "feat(api): import an image by URL through POST /media/imports and MCP media import"
  ```
  Add `log4j2.xml` and any MCP test whose counts changed.

---

### Task 9: `whoami` lists the weblogs a token can act on

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApi.java` (`me`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApiTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java` (the `whoami` description)
- Modify: `docs/api/README.md` (where `/me` is described)

**Interfaces:**
- Consumes: `UserManager.getWeblogPermissions(User)` (`WeblogPermission.getObjectId()` is the weblog's handle; read the actions with the list accessor `ObjectPermission` offers), and `WeblogManager.getWeblogByHandle(String)`.
- Produces: `GET /v1/me` JSON gains `"weblogs": [{"handle": "...", "name": "...", "permission": "ADMIN"|"POST"|"EDIT_DRAFT"}]`, sorted by handle.

- [ ] **Step 1: Write the failing tests**

  ```java
  @Test
  void anUnlimitedTokenSeesEveryWeblogItsUserBelongsTo() throws Exception {
      // permissions: ("travels", "admin"), ("food", "post"); weblogs exist for both; principal unpinned
      // expect weblogs == [{"handle":"food","name":"Food","permission":"POST"},
      //                    {"handle":"travels","name":"Travels","permission":"ADMIN"}]
  }

  @Test
  void aLimitedTokenSeesOnlyItsWeblog() throws Exception {
      // same permissions; principal scopeWeblog "travels" -> only travels
  }

  @Test
  void aUserWithNoWeblogsSeesAnEmptyList() throws Exception { /* no permissions -> "weblogs": [] */ }

  @Test
  void aPermissionWhoseWeblogIsGoneIsSkipped() throws Exception {
      // getWeblogByHandle("gone") returns null -> not listed, no error
  }
  ```
  Write them in `MetaApiTest`'s existing style (how it sets the principal and user). There is no pending-invitation flag in this tree's `WeblogPermission`, so the spec's "pending invitations excluded" holds by construction. Say so in a comment beside the query.

- [ ] **Step 2: Implement**

  In `MetaApi.me`, build the response with a `LinkedHashMap` (so the key order is stable) and add `"weblogs", weblogsFor(user, principal)`:
  ```java
  /**
   * The weblogs this token can act on: the one it is limited to, or every
   * weblog its user is a member of. This tree has no pending invitations
   * (WeblogPermission carries no such flag), so membership is the permission row.
   */
  private List<Map<String, String>> weblogsFor(User user, ApiPrincipal principal) throws WebloggerException {
      List<Map<String, String>> weblogs = new ArrayList<>();
      for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(user)) {
          String handle = permission.getObjectId();
          if (principal != null && principal.scopeWeblog() != null && !principal.scopeWeblog().equals(handle)) {
              continue;
          }
          Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(handle);
          if (weblog != null) {
              weblogs.add(Map.of("handle", weblog.getHandle(), "name", weblog.getName(),
                      "permission", highest(permission)));
          }
      }
      weblogs.sort(Comparator.comparing(w -> w.get("handle")));
      return weblogs;
  }

  private static String highest(WeblogPermission permission) {
      List<String> actions = permission.getActionsAsList();
      if (actions.contains(WeblogPermission.ADMIN)) {
          return "ADMIN";
      }
      return actions.contains(WeblogPermission.POST) ? "POST" : "EDIT_DRAFT";
  }
  ```
  `me` becomes `throws WebloggerException`. A failed lookup is an opaque 500, never a misleading empty list. A weblog name may be null; use `Objects.toString(weblog.getName(), "")` if `Map.of` would see null. Update the `whoami` tool description to `"Who this token belongs to: user name, whether the user is a site admin, the token's role and weblog limit, and the weblogs it can act on."`, and the README's `/me` description.

- [ ] **Step 3: Run the tests**

  Run: `mvn -q -o -pl app test -Dtest='MetaApiTest,Mcp*Test' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  Expected: PASS.

- [ ] **Step 4: Static gates, then commit**

  ```bash
  mvn -q -o -pl app verify -DskipTests -Djacoco.skip=true
  git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApi.java \
          app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApiTest.java \
          app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java docs/api/README.md
  git commit -m "feat(api): whoami lists the weblogs a token can act on"
  ```

---

### Task 10: Instructions, docs, and the end-to-end MCP tests at both context paths

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructions.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructionsTest.java`
- Modify: `docs/api/README.md` (a "Removing a value" section, events in Entries, Contents list)
- Modify: `docs/dev/api.md` (a new section)
- Modify: `it-selenium/src/test/java/org/apache/roller/it/McpIT.java`

**Interfaces:**
- Consumes: everything above. From `McpIT`: `mint`, `revoke`, `callTool`, `text`, `isError`, `restGet`, and `RollerIT`'s `WEBLOG_HANDLE`, `GLOBAL_CONFIG` lock and `openPath`/`$` (see `VirtualHostIT.removeWeblog`).

- [ ] **Step 1: The instructions, test first**

  In `McpInstructionsTest`, assert that `TEXT` contains `clear`, `media import`, `featuredImageId`, `weblogs create` and `EVENT`, and is still under 3,000 characters. Run it and see it fail. Then edit `TEXT`:
  - replace "and `weblogs list` (ADMIN tokens) to find handles" with "`whoami` also lists the weblogs your token can act on";
  - add these bullets:
  ```text
  - `weblogs create` makes a weblog you own; a wrong `theme` answers with the valid ones.
  - Updates never read null as a value. To remove a field, name it in `clear`, for example \
  clear: ["featuredImageId"].
  - A featured image is a media file: `media import` it from a public http(s) URL, then set \
  featuredImageId to its id.
  - For an event, set jsonLdType EVENT with eventStart and eventEnd as wall-clock times in the weblog's \
  zone, for example 2026-08-02T19:30, and eventLocation.
  ```
  Run: PASS.

- [ ] **Step 2: Docs**

  - **`docs/api/README.md`:**
    - **A "Removing a value" section after "Making a request":**
      - null never removes;
      - `clear` per resource, with the clearable lists and pairs;
      - the four refusals.
      - Add it to Contents.
    - **Entries:** the three event fields and their format.
    - **`McpDocsTest`:** make sure it still passes.
  - **`docs/dev/api.md`:** add `## New-weblog needs (step 2)` with these bullets:
    - **`WeblogSettingsRules` and `WeblogCreationRules`** are the one home for those checks. Both the JSP controllers and the API call them. The settings PATCH lays the request over `WeblogConfigBean` and stores it with the form's own `copyTo`. Violations are reported only when they involve a touched field.
    - **`ClearFields`:** null never removes; `clear` does. A new clearable field goes in the resource's `*_CLEARABLE` list and its `apply` switch.
    - **`RemoteImageFetcher`:** no bypass. Only a package-private `Policy` constructor, for tests. It must never gain a config switch. Any change to `PublicAddresses` updates the table test at the range's edges.
    - **Event times:** wall-clock times in the weblog's zone, editor and API alike (`EntryFieldRules.parseEventTime`/`formatWallClock`).
    - **Import logging:** `roller.media.import` logs the host only.

- [ ] **Step 3: `McpIT` scenarios**

  Add to `McpIT`:
  ```java
  @Test
  void aNewWeblogIsCreatedConfiguredAndPublishedThroughMcpAlone() throws Exception {
      Token token = mint("McpIT new weblog", "ADMIN", null);
      String handle = "mcpit" + (System.nanoTime() % 1_000_000_000L);
      try {
          JsonNode created = callTool(token.raw(), "weblogs", Map.of("action", "create", "handle", handle,
                  "name", "McpIT weblog", "theme", "journal", "timeZone", "Europe/Berlin"));
          assertFalse(isError(created), created.toString());

          JsonNode placed = callTool(token.raw(), "weblogs", Map.of("action", "update", "weblog", handle,
                  "placeType", "LodgingBusiness", "placeLocality", "Berlin", "placeLat", 52.52, "placeLng", 13.40));
          assertEquals("Berlin", JSON.readTree(text(placed)).get("placeLocality").asString(), text(placed));

          JsonNode event = callTool(token.raw(), "entries", Map.of("action", "create", "weblog", handle,
                  "title", "Konzert", "text", "<p>x</p>", "status", "PUBLISHED", "jsonLdType", "EVENT",
                  "eventStart", "2026-08-02T19:30", "eventEnd", "2026-08-02T22:00", "eventLocation", "Waldbühne",
                  "geoLatitude", 52.5146, "geoLongitude", 13.2283));
          assertFalse(isError(event), event.toString());
          JsonNode entry = JSON.readTree(text(event));
          assertEquals("2026-08-02T17:30:00Z", entry.get("eventStart").asString());
          String id = entry.get("id").asString();

          JsonNode cleared = callTool(token.raw(), "entries", Map.of("action", "update", "weblog", handle, "id", id,
                  "clear", List.of("geoLatitude", "geoLongitude")));
          JsonNode after = JSON.readTree(text(cleared));
          assertTrue(after.path("geoLatitude").isNull() || after.path("geoLatitude").isMissingNode(), after.toString());

          String permalinkHtml = client.send(HttpRequest.newBuilder(URI.create(entry.get("permalink").asString()))
                  .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
          // the Event block's startDate is the same instant as 19:30 Berlin
          java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"startDate\"\\s*:\\s*\"([^\"]+)\"")
                  .matcher(permalinkHtml);
          assertTrue(m.find(), "an Event block with a startDate");
          assertEquals(java.time.Instant.parse("2026-08-02T17:30:00Z"),
                  java.time.OffsetDateTime.parse(m.group(1)).toInstant());

          JsonNode me = JSON.readTree(text(callTool(token.raw(), "whoami", Map.of())));
          boolean listed = false;
          for (JsonNode w : me.get("weblogs")) {
              listed |= handle.equals(w.get("handle").asString()) && "ADMIN".equals(w.get("permission").asString());
          }
          assertTrue(listed, me.toString());
      } finally {
          removeWeblog(handle);
          revoke(token.id());
      }
  }

  @Test
  @ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
  void importingFromAnInternalAddressIsRefused() throws Exception {
      Token token = mint("McpIT import refusals", "POST", WEBLOG_HANDLE);
      try {
          for (String url : List.of("http://127.0.0.1/", "http://localhost/", "http://169.254.169.254/")) {
              JsonNode refused = callTool(token.raw(), "media",
                      Map.of("action", "import", "weblog", WEBLOG_HANDLE, "url", url));
              assertTrue(isError(refused), url);
              JsonNode problem = JSON.readTree(text(refused));
              assertEquals(400, problem.get("status").asInt(), url);
              assertEquals("That address is not on the public internet.", problem.get("detail").asString(), url);
          }
      } finally {
          revoke(token.id());
      }
  }
  ```
  - **Removing the weblog:** copy `VirtualHostIT.removeWeblog` into `McpIT` as `removeWeblog(String)`, including its login if `RollerIT` needs one. Check `RollerIT`'s browser and login helpers. If `McpIT` cannot drive a browser, delete the weblog through whatever cleanup `RollerIT` offers, and say so in the report.
  - **Uploads:** `uploads.enabled` is true at the IT baseline, and the `GLOBAL_CONFIG` read lock keeps `GlobalConfigMatrixIT` from switching it off mid-test (see `EditorSeoIT`'s header comment).
  - **The permalink:** it is absolute and built from `site.absoluteurl`. If the IT app's `site.absoluteurl` is not the IT `baseUrl()`, fetch the path part against `baseUrl()` instead.
  - **The theme:** if `journal` is not enabled in the IT app, use the first id the 400 lists.

- [ ] **Step 4: Unit suite and gates**

  ```bash
  pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR
  mvn -q -o verify
  ```
  Expected: the full unit suite, JaCoCo floors, diff coverage, PMD, CPD and SpotBugs all pass.

- [ ] **Step 5: Browser suite at both context paths**

  ```bash
  mvn -o verify -Pit -Dit.test=McpIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
  mvn -o verify -Pit -Dit.test=McpIT -Dit.context.path=roller -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
  mvn -o verify -Pit
  mvn -o verify -Pit -Dit.context.path=roller
  ```
  Expected: all green. The last two are the full browser suite, required because the Create Weblog page and the editor changed.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructions.java \
          app/src/test/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructionsTest.java \
          docs/api/README.md docs/dev/api.md it-selenium/src/test/java/org/apache/roller/it/McpIT.java
  git commit -m "docs(mcp): instructions and references for step 2; McpIT drives a new weblog end to end"
  ```

The wiki page RollerMcp is updated by the controller (the session that runs this plan), not by a task agent. It gets the new actions, the import limits, and where to turn uploads on.

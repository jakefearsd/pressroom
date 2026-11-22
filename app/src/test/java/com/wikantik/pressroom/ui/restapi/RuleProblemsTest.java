package com.wikantik.pressroom.ui.restapi;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.WeblogCreationRules;
import com.wikantik.pressroom.ui.controllers.WeblogSettingsRules;
import com.wikantik.pressroom.ui.controllers.core.CreateWeblogBean;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A weblog rule's violation says how the API answers it twice: the rule gives
 * it a status (a create's {@code status}) or a conflict flag (a settings
 * {@code conflict}), and its reason's code has a {@link ProblemType} with a
 * status of its own. The API reads the second for the status and the first
 * to tell a conflict from a 400 ({@link RuleProblems#refuse}), so the two must
 * agree or they drift apart unseen (spec 2026-11-21-foundation-cleanup-design,
 * Task 3, item 3). Each test drives the rules to every reason they have, and
 * fails naming a reason it could not reach.
 */
class RuleProblemsTest {

    @Test
    void everyCreationViolationsStatusIsItsCodesStatus() throws Exception {
        List<WeblogCreationRules.Violation> found = creationViolations();

        assertEquals(EnumSet.allOf(WeblogCreationRules.Reason.class),
                found.stream().map(WeblogCreationRules.Violation::reason)
                        .collect(() -> EnumSet.noneOf(WeblogCreationRules.Reason.class), Set::add, Set::addAll),
                "every reason was reached");
        for (WeblogCreationRules.Violation v : found) {
            assertEquals(RuleProblems.code(v.reason()).type().status(), v.status(), v.reason() + ": " + v.detail());
        }
    }

    @Test
    void everySettingsViolationIsAConflictExactlyWhenItsCodeIsA409() throws Exception {
        List<WeblogSettingsRules.Violation> found = settingsViolations();

        assertEquals(EnumSet.allOf(WeblogSettingsRules.Reason.class),
                found.stream().map(WeblogSettingsRules.Violation::reason)
                        .collect(() -> EnumSet.noneOf(WeblogSettingsRules.Reason.class), Set::add, Set::addAll),
                "every reason was reached");
        for (WeblogSettingsRules.Violation v : found) {
            assertEquals(RuleProblems.code(v.reason()).type().status() == 409, v.conflict(),
                    v.reason() + ": " + v.detail());
        }
    }

    private static List<WeblogCreationRules.Violation> creationViolations() throws Exception {
        UserManager users = mock(UserManager.class);
        WeblogManager weblogs = mock(WeblogManager.class);
        WeblogPageManager pages = mock(WeblogPageManager.class);
        when(users.getWeblogPermissions(any(User.class))).thenReturn(List.of(new WeblogPermission()));
        when(weblogs.getWeblogByHandle("taken", null)).thenReturn(new Weblog());
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        WeblogPage about = new WeblogPage();
        about.setSlug("about");
        about.setStatus(WeblogPage.PubStatus.PUBLISHED);
        when(weblogs.getSiteSpace()).thenReturn(site);
        when(pages.getPages(site)).thenReturn(List.of(about));

        List<WeblogCreationRules.Violation> found = new ArrayList<>();
        User user = new User();
        found.add(WeblogCreationRules.mayCreate(user, creationContext(false, true, users, weblogs, pages)));
        found.add(WeblogCreationRules.mayCreate(user, creationContext(true, false, users, weblogs, pages)));

        WeblogCreationRules.Context context = creationContext(true, true, users, weblogs, pages);
        CreateWeblogBean everythingWrong = new CreateWeblogBean();
        everythingWrong.setHandle(" ");
        everythingWrong.setName("n".repeat(WeblogCreationRules.MAX_TEXT + 1));
        everythingWrong.setTheme("frontpage");
        everythingWrong.setLocale("xx_YY");
        everythingWrong.setTimeZone("Mars/Olympus_Mons");
        found.addAll(WeblogCreationRules.check(everythingWrong, context));
        for (String handle : List.of("my blog", "api", "taken", "about")) {
            CreateWeblogBean bean = new CreateWeblogBean();
            bean.setHandle(handle);
            bean.setName("A weblog");
            bean.setTheme("journal");
            found.addAll(WeblogCreationRules.check(bean, context));
        }
        return found;
    }

    private static WeblogCreationRules.Context creationContext(boolean allowed, boolean groupBlogging,
            UserManager users, WeblogManager weblogs, WeblogPageManager pages) {
        return new WeblogCreationRules.Context(allowed, groupBlogging, "A-Za-z0-9", Set.of("api"),
                List.of("journal"), users, weblogs, pages);
    }

    private static List<WeblogSettingsRules.Violation> settingsViolations() throws Exception {
        BusinessManager businesses = mock(BusinessManager.class);
        WeblogManager weblogs = mock(WeblogManager.class);
        WeblogPageManager pages = mock(WeblogPageManager.class);
        Weblog own = new Weblog();
        own.setId("own-id");
        own.setHandle("myblog");
        Weblog other = new Weblog();
        other.setHandle("other");
        when(weblogs.getWeblogByCustomDomain("taken.example.org")).thenReturn(other);
        when(weblogs.getWeblogByCustomDomain("down.example.org")).thenThrow(new WebloggerException("down"));
        WeblogSettingsRules.Context context =
                new WeblogSettingsRules.Context(own, 30, "https://blog.example.com", businesses, weblogs, pages);

        List<WeblogSettingsRules.Violation> found = new ArrayList<>();
        WeblogConfigBean everythingWrong = new WeblogConfigBean();
        everythingWrong.setEntryDisplayCount(0);
        everythingWrong.setLocale("xx_YY");
        everythingWrong.setTimeZone("Mars/Olympus_Mons");
        everythingWrong.setEntryOrder("SIDEWAYS");
        everythingWrong.setCopyright("c".repeat(WeblogSettingsRules.MAX_TEXT + 1));
        everythingWrong.setNewsletterListUuid("not-a-uuid");
        everythingWrong.setBusinessId("no-such-business");
        everythingWrong.setFrontPageId("no-such-page");
        everythingWrong.setPlaceType("Castle");
        everythingWrong.setPlaceCountry("Atlantis");
        everythingWrong.setPlaceLat("91");
        everythingWrong.setPlaceLng("181");
        everythingWrong.setBookingUrl("ftp://example.org");
        everythingWrong.setCustomDomain("not a host");
        found.addAll(WeblogSettingsRules.check(everythingWrong, context));
        for (String customDomain : List.of("blog.example.com", "taken.example.org", "down.example.org")) {
            WeblogConfigBean bean = new WeblogConfigBean();
            bean.setEntryDisplayCount(15);
            bean.setPlaceType("LodgingBusiness");
            bean.setCustomDomain(customDomain);
            found.addAll(WeblogSettingsRules.check(bean, context));
        }
        return found;
    }
}

-- Do any STORED templates reference something a removal wave deleted?
--
-- Shipped themes live in the repo and get swept by each wave. Custom themes do
-- not: they live as rows in this database, where no compiler, test or grep can
-- reach them. Velocity here is lenient (velocity.properties sets no
-- runtime.references.strict and turns off runtime.log.invalid.reference), so a
-- stored template calling a deleted macro or reading a deleted property does
-- NOT fail -- it prints the reference as literal text onto the public page,
-- with no exception, no log line, and no failing test.
--
-- WHEN THIS MATTERS, and when it does not:
-- As of W2 there is no production instance -- the first deploy will build its
-- schema from the migration chain on an empty database, so no legacy stored
-- template can exist and W1/W2 have nothing to break. This script is therefore
-- NOT a W1/W2 pre-deploy gate; it is forward-looking. It earns its keep the
-- first time BOTH of these are true:
--   1. themes.customtheme.allowed has been switched on and a weblog converted
--      (or a Templates/Stylesheet row saved), AND
--   2. a later wave deletes a macro, model method or weblog property.
-- Extend the pattern list in query 2 with whatever that wave removes.
--
-- Run it:
--   docker compose exec -T postgres psql -U pressroom -d pressroom \
--     < bin/db/check-stored-templates.sql
--
-- Query 1 is the whole answer for most installs. If it returns 0 rows there is
-- nothing stored to break and queries 2-3 will be empty by construction.

\echo '== 1. Can any stored template exist at all? =='
-- themes.customtheme.allowed defaults false, and EVERY row-creating path
-- (Templates screen, Stylesheet screen, custom-theme conversion) sits behind
-- it. A zero here plus a false setting means the answer is no.
SELECT
    (SELECT count(*) FROM weblog WHERE editortheme = 'custom')        AS custom_theme_weblogs,
    (SELECT count(*) FROM weblog_custom_template)                     AS stored_templates,
    (SELECT count(*) FROM custom_template_rendition)                  AS stored_renditions,
    COALESCE((SELECT value FROM pressroom_properties
              WHERE name = 'themes.customtheme.allowed'), '(unset -> default false)')
                                                                      AS customtheme_allowed;

\echo ''
\echo '== 2. Stored templates referencing something W1 or W2 removed =='
SELECT w.handle,
       t.name AS template_name,
       t.action,
       r.templatelang,
       concat_ws(', ',
         -- W1: comments
         CASE WHEN r.template ILIKE '%showWeblogEntryComments%'       THEN 'W1 #showWeblogEntryComments' END,
         CASE WHEN r.template ILIKE '%showMobileWeblogEntryComments%' THEN 'W1 #showMobileWeblogEntryComments' END,
         CASE WHEN r.template ILIKE '%showWeblogEntryCommentForm%'    THEN 'W1 #showWeblogEntryCommentForm' END,
         CASE WHEN r.template ILIKE '%commentCount%'                  THEN 'W1 $…commentCount' END,
         CASE WHEN r.template ILIKE '%commentsStillAllowed%'          THEN 'W1 $entry.commentsStillAllowed' END,
         CASE WHEN r.template ILIKE '%recentComments%'                THEN 'W1 $weblog.recentComments' END,
         CASE WHEN r.template ILIKE '%feed.comments%'                 THEN 'W1 $url.feed.comments.*' END,
         CASE WHEN r.template ILIKE '%url.comment%'                   THEN 'W1 $url.comment(s)' END,
         CASE WHEN r.template ILIKE '%commentAuthenticator%'          THEN 'W1 $url.commentAuthenticator' END,
         CASE WHEN r.template ILIKE '%themes/base.css%'               THEN 'W1 themes/base.css (404s)' END,
         CASE WHEN r.template ILIKE '%popupcomments%'                 THEN 'W1 _popupcomments' END,
         -- W2: calendar
         CASE WHEN r.template ILIKE '%showWeblogEntryCalendar%'       THEN 'W2 #showWeblogEntryCalendar(Big)' END,
         CASE WHEN r.template ILIKE '%calendarModel%'                 THEN 'W2 $calendarModel' END,
         -- W2: multi-locale
         CASE WHEN r.template ILIKE '%enableMultiLang%'               THEN 'W2 $weblog.enableMultiLang' END,
         CASE WHEN r.template ILIKE '%showAllLangs%'                  THEN 'W2 $weblog.showAllLangs' END,
         -- W2: blogger API category
         CASE WHEN r.template ILIKE '%bloggerCategory%'               THEN 'W2 $weblog.bloggerCategory' END,
         -- W2: feeds
         CASE WHEN r.template ILIKE '%showRSSFeedsList%'              THEN 'W2 #showRSSFeedsList' END,
         CASE WHEN r.template ILIKE '%showEntriesRSS20%'              THEN 'W2 #showEntriesRSS20' END,
         CASE WHEN r.template ILIKE '%showCommentsRSS20%'             THEN 'W1 #showCommentsRSS20' END,
         CASE WHEN r.template ILIKE '%showCommentsAtom10%'            THEN 'W1 #showCommentsAtom10' END,
         CASE WHEN r.template ILIKE '%showFilesAtom10%'               THEN 'W2 #showFilesAtom10' END,
         CASE WHEN r.template ILIKE '%feed.entries.rss%'              THEN 'W2 $url.feed.entries.rss' END,
         CASE WHEN r.template ILIKE '%feed.files%'                    THEN 'W2 $url.feed.files.*' END,
         CASE WHEN r.template ILIKE '%openSearch%'                    THEN 'W2 $url.openSearch* (never existed)' END,
         -- W2: legacy analytics
         CASE WHEN r.template ILIKE '%analyticsCode%'                 THEN 'W2 $weblog.analyticsCode' END,
         CASE WHEN r.template ILIKE '%defaultAnalyticsTrackingCode%'  THEN 'W2 $config.defaultAnalyticsTrackingCode' END,
         CASE WHEN r.template ILIKE '%analyticsOverrideAllowed%'      THEN 'W2 $config.analyticsOverrideAllowed' END,
         CASE WHEN r.template ILIKE '%commentHtmlAllowed%'            THEN 'W1 $config.commentHtmlAllowed' END,
         CASE WHEN r.template ILIKE '%commentAutoFormat%'             THEN 'W1 $config.commentAutoFormat' END,
         -- earlier waves, same hazard class
         CASE WHEN r.template ILIKE '%getHotWeblogs%'                 THEN 'pre-W1 $site.getHotWeblogs' END,
         CASE WHEN r.template ILIKE '%getMostCommentedWeblogs%'       THEN 'W1 $site.getMostCommentedWeblogs' END,
         CASE WHEN r.template ILIKE '%mediaFilesPager%'               THEN 'W2 $model.mediaFilesPager' END
       ) AS broken_references
FROM custom_template_rendition r
JOIN weblog_custom_template t ON t.id = r.templateid
JOIN weblog w                 ON w.id = t.websiteid
WHERE r.template ILIKE ANY (ARRAY[
        '%showWeblogEntryComments%','%showMobileWeblogEntryComments%','%showWeblogEntryCommentForm%',
        '%commentCount%','%commentsStillAllowed%','%recentComments%','%feed.comments%','%url.comment%',
        '%commentAuthenticator%','%themes/base.css%','%popupcomments%',
        '%showWeblogEntryCalendar%','%calendarModel%',
        '%enableMultiLang%','%showAllLangs%','%bloggerCategory%',
        '%showRSSFeedsList%','%showEntriesRSS20%','%showCommentsRSS20%','%showCommentsAtom10%',
        '%showFilesAtom10%','%feed.entries.rss%','%feed.files%','%openSearch%',
        '%analyticsCode%','%defaultAnalyticsTrackingCode%','%analyticsOverrideAllowed%',
        '%commentHtmlAllowed%','%commentAutoFormat%',
        '%getHotWeblogs%','%getMostCommentedWeblogs%','%mediaFilesPager%'
      ])
ORDER BY w.handle, t.name;

\echo ''
\echo '== 3. Broad net: stored templates mentioning a removed feature by name =='
-- Wider than query 2 on purpose: catches a theme author''s own macros and
-- spellings the pattern list missed. Prose inside a ## Velocity comment is a
-- false positive -- read the excerpt before acting.
SELECT w.handle,
       t.name AS template_name,
       substring(r.template from '(?i).{0,50}(comment|calendar|multilang|rss|analyticscode).{0,50}') AS excerpt
FROM custom_template_rendition r
JOIN weblog_custom_template t ON t.id = r.templateid
JOIN weblog w                 ON w.id = t.websiteid
WHERE r.template ~* '(comment|calendar|multilang|rss|analyticscode)'
ORDER BY w.handle, t.name;

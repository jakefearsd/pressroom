-- Migration: a user's role, a user's permission on a weblog and a category
-- name within a weblog are each unique, and the database says so.
--
-- The managers checked "does it exist?" and the INSERT followed at commit,
-- with nothing enforcing the answer, so a double-submitted form or a retried
-- API call stored two rows. Every lookup of the pair (getSingleResult) then
-- threw NonUniqueResultException: a 500 on every page that asked for that
-- user's permission or that category, and a role revokeRole could never
-- take away. V002 indexed userrole(username) and weblogcategory(websiteid)
-- only. This migration
--   1. drops duplicate roles: (username, rolename) twice is the same row
--      twice; the lowest id stays.
--   2. merges duplicate permissions (username, objectid, objecttype) into
--      the oldest row (datecreated, then id), which keeps the union of their
--      actions in first-granted order -- what granting them one after the
--      other leaves, since grantWeblogPermission adds actions to an existing
--      row.
--   3. renames duplicate categories (websiteid, name) rather than deleting
--      any, because each may hold entries: the one with the most entries
--      (then the lowest position, then the lowest id) keeps the name, and
--      every other takes the first free "<name> N", N = 2, 3, ..., cut to
--      fit varchar(255). No entry changes category.
--   4. adds the unique indexes ur_username_rolename_uq,
--      perm_username_object_uq and wc_weblog_name_uq.
--
-- Idempotent: a second run finds no duplicate, and the indexes exist.

DELETE FROM userrole r
 USING userrole keep
 WHERE keep.username = r.username
   AND keep.rolename = r.rolename
   AND keep.id < r.id;

DO $$
DECLARE
    grp     record;
    keep_id varchar(48);
    merged  text;
BEGIN
    FOR grp IN
        SELECT username, objectid, objecttype
          FROM pressroom_permission
         GROUP BY username, objectid, objecttype
        HAVING count(*) > 1
    LOOP
        SELECT id INTO keep_id
          FROM pressroom_permission
         WHERE username = grp.username
           AND objectid IS NOT DISTINCT FROM grp.objectid
           AND objecttype IS NOT DISTINCT FROM grp.objecttype
         ORDER BY datecreated, id
         LIMIT 1;

        SELECT string_agg(action, ',' ORDER BY first_seen) INTO merged
          FROM (SELECT action, min(seq) AS first_seen
                  FROM (SELECT trim(a.action) AS action,
                               row_number() OVER (ORDER BY p.datecreated, p.id, a.pos) AS seq
                          FROM pressroom_permission p
                         CROSS JOIN LATERAL unnest(string_to_array(p.actions, ','))
                               WITH ORDINALITY AS a(action, pos)
                         WHERE p.username = grp.username
                           AND p.objectid IS NOT DISTINCT FROM grp.objectid
                           AND p.objecttype IS NOT DISTINCT FROM grp.objecttype) granted
                 WHERE action <> ''
                 GROUP BY action) firsts;

        UPDATE pressroom_permission SET actions = merged WHERE id = keep_id;
        DELETE FROM pressroom_permission
         WHERE username = grp.username
           AND objectid IS NOT DISTINCT FROM grp.objectid
           AND objecttype IS NOT DISTINCT FROM grp.objecttype
           AND id <> keep_id;
    END LOOP;
END
$$;

DO $$
DECLARE
    dup       record;
    n         integer;
    candidate text;
BEGIN
    FOR dup IN
        SELECT id, websiteid, name
          FROM (SELECT c.id, c.websiteid, c.name,
                       row_number() OVER (PARTITION BY c.websiteid, c.name
                                          ORDER BY (SELECT count(*) FROM weblogentry e
                                                     WHERE e.categoryid = c.id) DESC,
                                                   c.position, c.id) AS rank
                  FROM weblogcategory c) ranked
         WHERE rank > 1
         ORDER BY websiteid, name, rank
    LOOP
        n := 2;
        LOOP
            candidate := left(dup.name, 255 - length(n::text) - 1) || ' ' || n;
            EXIT WHEN NOT EXISTS (
                SELECT 1 FROM weblogcategory
                 WHERE websiteid = dup.websiteid AND name = candidate);
            n := n + 1;
        END LOOP;
        UPDATE weblogcategory SET name = candidate WHERE id = dup.id;
    END LOOP;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS ur_username_rolename_uq ON userrole (username, rolename);
CREATE UNIQUE INDEX IF NOT EXISTS perm_username_object_uq
    ON pressroom_permission (username, objectid, objecttype) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS wc_weblog_name_uq ON weblogcategory (websiteid, name);

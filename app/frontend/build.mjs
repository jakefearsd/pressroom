/* Bundles the editor into the webapp root -- the ONE static location this
   deployment actually serves, and NOT a classpath one.

   DispatcherServlet is mapped to *.rol only (application.properties), so
   Spring MVC's static-resource handling never sees an asset request and
   classpath:/static is dead here. The servlet-spec classpath location,
   WEB-INF/classes/META-INF/resources, is dead too: the app ships as a Boot
   executable WAR, where WEB-INF/classes is a NESTED classpath entry that
   Boot's StaticResourceJars scan cannot open, so nothing ever registers it
   as a web resource. Both were tried; both 404'd, and the browser suite is
   what said so -- a bundle in the wrong place builds, packages and ships,
   and then fails with one console line on the one page that needs it.

   The webapp root works in both shapes that matter: maven-war-plugin copies
   src/main/webapp into the WAR, and spring-boot:run (./roller dev) uses
   src/main/webapp as the document root directly. The files themselves are
   build outputs and are git-ignored; EditorBundlePomTest pins that.

   Two bundles. roller-editor.js serves the two login-gated editors.
   roller-content.js serves READERS: weblog.vm's content-asset macro injects it
   on a public page that has a code block or a content heading to enhance
   (highlighting, copy buttons, line numbers, heading anchors). Being public,
   it is also the one file under /roller-ui that ControlPlaneHostFilter leaves
   on a weblog's custom domain -- a redirect to the site host would be
   cross-origin and refused by every theme's script-src 'self'. */
import { build } from 'esbuild';

const common = {
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['es2020'],
  legalComments: 'none',
  logLevel: 'info'
};

await build({
  ...common,
  entryPoints: ['src/editor.js'],
  globalName: 'RollerEditor',
  outfile: '../src/main/webapp/roller-ui/scripts/roller-editor.js'
});

await build({
  ...common,
  entryPoints: ['src/content.js'],
  globalName: 'RollerContent',
  outfile: '../src/main/webapp/roller-ui/scripts/roller-content.js'
});

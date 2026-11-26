/* Bundles the admin and reader scripts into the webapp root -- the ONE static location this
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
   src/main/webapp into the WAR, and spring-boot:run (./pressroom dev) uses
   src/main/webapp as the document root directly. The files themselves are
   build outputs and are git-ignored; EditorBundlePomTest pins that.

   Two bundles. admin.js (src/admin/main.tsx, the React admin) lands in
   pressroom-ui/app/ beside the .rol shell route, unhashed and with ?v= cache
   busting like the others; `node build.mjs --watch` rebuilds only it, and
   unminified with a source map, for the dev loop.
   pressroom-content.js serves READERS: weblog.vm's content-asset macro injects it
   on a public page that has a code block or a content heading to enhance
   (highlighting, copy buttons, line numbers, heading anchors). Being public,
   it is also the one file under /pressroom-ui that ControlPlaneHostFilter leaves
   on a weblog's custom domain -- a redirect to the site host would be
   cross-origin and refused by every theme's script-src 'self'. */
import { readFileSync } from 'node:fs';
import { build, context } from 'esbuild';

const admin = {
  entryPoints: ['src/admin/main.tsx'],
  bundle: true,
  format: 'iife',
  target: ['es2020'],
  legalComments: 'none',
  logLevel: 'info',
  define: { 'process.env.NODE_ENV': '"production"' },
  outfile: '../src/main/webapp/pressroom-ui/app/admin.js'
};

if (process.argv.includes('--watch')) {
  const watcher = await context({ ...admin, sourcemap: true });
  await watcher.watch();
  console.log('watching src/admin; reload the browser after each rebuild');
  await new Promise(() => {});
}

const common = {
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['es2020'],
  legalComments: 'none',
  logLevel: 'info'
};

const hljsVersion = JSON.parse(readFileSync('node_modules/highlight.js/package.json', 'utf8')).version;

await build({
  ...common,
  entryPoints: ['src/content.js'],
  globalName: 'PressroomContent',
  banner: { js: `/*! highlight.js ${hljsVersion} | BSD-3-Clause | Copyright (c) 2006, Ivan Sagalaev */` },
  outfile: '../src/main/webapp/pressroom-ui/scripts/pressroom-content.js'
});

await build({ ...admin, minify: true });

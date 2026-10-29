<%-- 
This default stuff goes in the HTML head element of each page
You can override it with your own file via WEB-INF/tiles-def.xml

The /webjars/ versions below must match the webjar dependency versions in
app/pom.xml. WebjarReferenceTest fails the build if they drift, because a
mismatch 404s the asset on every page that includes this file.
--%>

<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>
<%@ page session="false" %>

<script src="<c:url value='/webjars/jquery/4.0.0/jquery.min.js'/>"></script>

<script src="<c:url value='/webjars/jquery-validation/1.21.0/jquery.validate.min.js'/>"></script>

<link href="<c:url value='/webjars/bootstrap/5.3.8/css/bootstrap.min.css'/>" rel="stylesheet" />
<script src="<c:url value='/webjars/bootstrap/5.3.8/js/bootstrap.bundle.min.js'/>"></script>

<link href="<c:url value='/webjars/bootstrap-icons/1.13.2/font/bootstrap-icons.min.css'/>" rel="stylesheet" />

<script src="<c:url value='/webjars/clipboard.js/2.0.11/clipboard.min.js'/>"></script>

<%-- "Quiet Instrument" design tokens (docs/design/design-system.md). Loads
     after Bootstrap so its custom properties are available, and before
     roller.css so roller.css can override on top of it. --%>
<link rel="stylesheet" media="all" href='<c:url value="/roller-ui/styles/roller-tokens.css"/>' />

<link rel="stylesheet" media="all" href='<c:url value="/roller-ui/styles/roller.css"/>' />

<script src="<c:url value="/theme/scripts/roller.js"/>"></script>

<%-- The Markdown editor. Built from app/frontend by Maven (esbuild) into
     roller-ui/scripts/roller-editor.js under the webapp root -- see
     app/frontend/build.mjs for why that path and no classpath one. Loaded
     only on the two screens that mount an editor: the bundle is a few
     hundred KB and every other admin page would pay for it.

     Keyed off ${tile_content}, the content-area JSP path PressroomViewResolver
     publishes as a request attribute for the layout, rather than off the
     request URL: that is the same value the layout uses to decide what it is
     rendering, so the two cannot disagree. If a third screen ever mounts an
     editor, add it here; the failure mode is loud (the page's own inline
     script throws "PressroomEditor is not defined"), not silent.

     Last in this file so roller-editor.css can override roller.css. --%>
<c:if test="${fn:contains(tile_content, 'EntryEdit.jsp') or fn:contains(tile_content, 'PageEdit.jsp')}">
<%-- ?v=<application version>: the bundle's name is unversioned and the app
     sends no Cache-Control, so a browser or CDN would keep a bundle from the
     previous release (one that defines other globals). The version is the
     Colophon's, the one the footer shows; c:param encodes it. --%>
<% request.setAttribute("editorBundleVersion", com.wikantik.pressroom.business.Colophon.of(
       null, null, java.time.Clock.systemDefaultZone()).version()); %>
<script src="<c:url value='/roller-ui/scripts/roller-editor.js'><c:param name='v' value='${editorBundleVersion}'/></c:url>"></script>
<link rel="stylesheet" media="all" href="<c:url value='/roller-ui/styles/roller-editor.css'/>" />
</c:if>


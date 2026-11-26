<%-- 
This default stuff goes in the HTML head element of each page
You can override it with your own file via WEB-INF/tiles-def.xml

The /webjars/ versions below must match the webjar dependency versions in
app/pom.xml. WebjarReferenceTest fails the build if they drift, because a
mismatch 404s the asset on every page that includes this file.
--%>

<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>
<%@ page session="false" %>

<%-- A public page a search engine reads sets metaDescription in its
     controller (SiteDirectoryController); admin-typed, so escaped. --%>
<c:if test="${not empty metaDescription}">
<meta name="description" content="${fn:escapeXml(metaDescription)}">
</c:if>

<script src="<c:url value='/webjars/jquery/4.1.0/jquery.min.js'/>"></script>

<link href="<c:url value='/webjars/bootstrap/5.3.8/css/bootstrap.min.css'/>" rel="stylesheet" />
<script src="<c:url value='/webjars/bootstrap/5.3.8/js/bootstrap.bundle.min.js'/>"></script>

<link href="<c:url value='/webjars/bootstrap-icons/1.13.2/font/bootstrap-icons.min.css'/>" rel="stylesheet" />

<script src="<c:url value='/webjars/clipboard.js/2.0.11/clipboard.min.js'/>"></script>

<%-- "Quiet Instrument" design tokens (docs/design/design-system.md). Loads
     after Bootstrap so its custom properties are available, and before
     pressroom.css so pressroom.css can override on top of it. --%>
<link rel="stylesheet" media="all" href='<c:url value="/pressroom-ui/styles/pressroom-tokens.css"/>' />

<link rel="stylesheet" media="all" href='<c:url value="/pressroom-ui/styles/pressroom.css"/>' />

<script src="<c:url value="/theme/scripts/pressroom.js"/>"></script>



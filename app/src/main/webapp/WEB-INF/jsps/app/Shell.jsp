<%--
  The React admin's shell: the stylesheets head.jsp loads (same order), then the editor surface's, one
  JSON bootstrap block, and the bundle. No tiles. Every URL goes through c:url,
  so it carries the context path. bootstrapJson is escaped for a script element
  in Java (AppBootstrap), so it is written out as is.
--%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ page session="false" contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pressroom</title>
<link href="<c:url value='/webjars/bootstrap/5.3.8/css/bootstrap.min.css'/>" rel="stylesheet" />
<link href="<c:url value='/webjars/bootstrap-icons/1.13.2/font/bootstrap-icons.min.css'/>" rel="stylesheet" />
<link rel="stylesheet" media="all" href="<c:url value='/pressroom-ui/styles/pressroom-tokens.css'/>" />
<link rel="stylesheet" media="all" href="<c:url value='/pressroom-ui/styles/pressroom.css'/>" />
<link rel="stylesheet" media="all" href="<c:url value='/pressroom-ui/styles/pressroom-editor.css'/>" />
</head>
<body>
<div id="pressroom-root"></div>
<script type="application/json" id="pressroom-bootstrap"><c:out value="${bootstrapJson}" escapeXml="false"/></script>
<script src="<c:url value='/pressroom-ui/app/admin.js'><c:param name='v' value='${appVersion}'/></c:url>"></script>
</body>
</html>

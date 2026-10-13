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

package org.apache.roller.weblogger.ui.rendering.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.util.DateUtil;

/**
 * Utility class to localize the modification date header-related logic.
 */
public final class ModDateHeaderUtil {

	private static final Logger log = LoggerFactory.getLogger(ModDateHeaderUtil.class);

	/**
	 * Instantiates a new mod date header util.
	 */
	private ModDateHeaderUtil() {
	}

	/**
	 * Sets the HTTP response status to 304 (NOT MODIFIED) if the request
	 * contains an If-Modified-Since header that specifies a time that is at or
	 * after the time specified by the value of lastModifiedTimeMillis
	 * <em>truncated to second granularity</em>. Returns true if the response
	 * status was set, false if not.
	 *
	 * @param request
	 *            the request
	 * @param response
	 *            the response
	 * @param lastModifiedTimeMillis
	 *            the last modified time millis
	 *
	 * @return true if a response status was sent, false otherwise.
	 */
	public static boolean respondIfNotModified(HttpServletRequest request,
			HttpServletResponse response, long lastModifiedTimeMillis) {

		long sinceDate;
		try {
			sinceDate = request.getDateHeader("If-Modified-Since");
		} catch (IllegalArgumentException ex) {
			// this indicates there was some problem parsing the header value as
			// a date
			return false;
		}

		// truncate to seconds
		lastModifiedTimeMillis -= lastModifiedTimeMillis % RollerConstants.SEC_IN_MS;

		if (log.isDebugEnabled()) {
			SimpleDateFormat dateFormat = new SimpleDateFormat(
					"EEE MMM dd 'at' h:mm:ss a", Locale.US); // debug log line, English like the rest of the log
			log.debug("since date = {}",
					DateUtil.format(new Date(sinceDate), dateFormat));
			log.debug("last mod date (trucated to seconds) = {}",
					DateUtil.format(new Date(lastModifiedTimeMillis),
							dateFormat));
		}

		if (lastModifiedTimeMillis <= sinceDate) {

			log.debug("NOT MODIFIED {}", request.getRequestURL());

			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);

			// Last-Modified must describe the resource, not echo whatever
			// string the client sent as If-Modified-Since -- the caller
			// already knows the resource's own last-modified time.
			response.setDateHeader("Last-Modified", lastModifiedTimeMillis);

			return true;
		} else {
			return false;
		}
	}

	/**
	 * Set the Last-Modified header using the given time in milliseconds. Note
	 * that because the header has the granularity of one second, the value will
	 * get truncated to the nearest second that does not exceed the provided
	 * value.
	 * <p/>
	 * This will also set the Expires header to a date in the past. This forces
	 * clients to revalidate the cache each time.
	 *
	 * @param response
	 *            the response
	 * @param lastModifiedTimeMillis
	 *            the last modified time millis
	 */
	public static void setLastModifiedHeader(HttpServletResponse response,
			long lastModifiedTimeMillis) {

		response.setDateHeader("Last-Modified", lastModifiedTimeMillis);
		// Force clients to revalidate each time
		// See RFC 2616 (HTTP 1.1 spec) secs 14.21, 13.2.1
		response.setDateHeader("Expires", 0);
		// We may also want this (See 13.2.1 and 14.9.4)
		// response.setHeader("Cache-Control","must-revalidate");

	}

}

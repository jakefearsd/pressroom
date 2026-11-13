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

package com.wikantik.pressroom.ui.rendering.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.search.SearchHit;
import com.wikantik.pressroom.business.search.SearchHits;
import com.wikantik.pressroom.business.search.SearchSpec;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogCategoryWrapper;
import com.wikantik.pressroom.ui.rendering.pagers.SearchResultsPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesPager;
import com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest;

/**
 * Extends normal page renderer model to represent search results.
 * 
 * Also adds some new methods which are specific only to search results.
 */
public class SearchResultsModel extends PageModel {

	private static final Logger log = LoggerFactory.getLogger(SearchResultsModel.class);

	public static final int RESULTS_PER_PAGE = 10;

	// the original search request
	WeblogSearchRequest searchRequest = null;
	private URLStrategy urlStrategy = null;

	/** How many of this blog's own matches the blog view shows. */
	static final int OWN_IN_BLOG_VIEW = 8;
	/** How many other-blog matches the blog view shows. */
	static final int OTHERS_IN_BLOG_VIEW = 5;

	// the pager used by the 3.0+ rendering system
	private SearchResultsPager pager = null;

	private boolean siteScope = false;
	private boolean blogOnly = false;
	private List<SearchHit> ownHits = List.of();
	private int ownTotal = 0;
	private List<SearchHit> otherHits = List.of();
	private boolean anyWordFallback = false;
	private Map<String, Integer> perBlog = Map.of();
	private int offset = 0;
	private Set<String> categories = new TreeSet<>();
	private String errorMessage = "";

	@Override
	public void init(Map<String, Object> initData) throws WebloggerException {

		// we expect the init data to contain a searchRequest object
		searchRequest = (WeblogSearchRequest) initData.get("searchRequest");
		if (searchRequest == null) {
			throw new WebloggerException("expected searchRequest from init data");
		}

		// required, no fallback (the parent init below also takes the facade)
		urlStrategy = ModelLoader.requireUrlStrategy(initData);

		// let parent initialize
		super.init(initData);

		String handle = searchRequest.getWeblogHandle();
		Weblog searched = searchRequest.getWeblog();
		siteScope = isTheSite(searched, handle);
		if (searched != null && searched.isSiteSpace()) {
			// The site's one address always shows the site's results: the site
			// space has no posts and no categories, so scope=blog and cat are
			// dropped here, before the query, the view and the pager's links
			// read them. (The aggregated front-page blog is a real blog, whose
			// scope=blog pages its own posts.)
			searchRequest.setScopeBlog(false);
			searchRequest.setWeblogCategoryName(null);
		}
		blogOnly = searchRequest.isBlogOnly();

		// if there is no query, then we are done
		if (searchRequest.getQuery() == null) {
			pager = new SearchResultsPager(urlStrategy, searchRequest, false);
			return;
		}

		IndexManager indexMgr = weblogger.getIndexManager();
		boolean paged = siteScope || blogOnly;
		try {
			if (paged) {
				// The one place paging is worked out: the offset asked for, from the
				// page number alone. The index clamps a page past its end by itself,
				// so the model never second-guesses it from the total (which drops
				// stale documents and so can disagree with what find returned).
				offset = Math.max(0, searchRequest.getPageNum()) * RESULTS_PER_PAGE;
				SearchSpec.Scope scope = (siteScope && !blogOnly)
						? SearchSpec.Scope.SITE : SearchSpec.Scope.BLOG;
				SearchHits found = indexMgr.find(spec(scope, handle, offset, RESULTS_PER_PAGE), urlStrategy);
				takeOwn(found);
				perBlog = found.perBlog();
				// find answers a request past its end with the first page; show it as page 0
				if (offset > 0 && offset >= ownTotal && !ownHits.isEmpty()) {
					offset = 0;
				}
			} else {
				SearchHits own = indexMgr.find(
						spec(SearchSpec.Scope.BLOG, handle, 0, OWN_IN_BLOG_VIEW), urlStrategy);
				takeOwn(own);
				// Never the site: isTheSite made its search the paged site view
				// above, so the site space never asks for "other" blogs.
				if (searched != null && !Boolean.FALSE.equals(searched.getShowMoreFromSite())) {
					SearchHits others = indexMgr.find(
							spec(SearchSpec.Scope.OTHER_BLOGS, handle, 0, OTHERS_IN_BLOG_VIEW), urlStrategy);
					otherHits = others.hits();
					anyWordFallback |= others.anyWordFallback();
				}
			}
		} catch (WebloggerException | IllegalArgumentException we) {
			errorMessage = we.getMessage();
			ownHits = List.of();
			otherHits = List.of();
			ownTotal = 0;
			anyWordFallback = false;
			perBlog = Map.of();
			categories = new TreeSet<>();
			offset = 0;
		}

		// search completed, setup pager based on results
		pager = new SearchResultsPager(urlStrategy, searchRequest,
			paged && ownTotal > offset + RESULTS_PER_PAGE, offset / RESULTS_PER_PAGE);
	}

	/**
	 * Whether the searched weblog is the site, whose search is site-wide: the
	 * site space (its search is {@code /search}), or the legacy aggregated
	 * front-page blog. The one notion of "the site" in this model; the site
	 * has no "other" blogs to add.
	 */
	private static boolean isTheSite(Weblog searched, String handle) {
		return (searched != null && searched.isSiteSpace())
				|| WebloggerRuntimeConfig.isSiteWideWeblog(handle);
	}

	private SearchSpec spec(SearchSpec.Scope scope, String handle, int from, int size) {
		return new SearchSpec(searchRequest.getQuery(), scope, handle,
				searchRequest.getWeblogCategoryName(), from, size, searchRequest.getLocale());
	}

	private void takeOwn(SearchHits found) {
		ownHits = found.hits();
		ownTotal = found.total();
		anyWordFallback = found.anyWordFallback();
		categories = found.categories();
	}

	/**
	 * Is this page showing search results?
	 */
	@Override
	public boolean isSearchResults() {
		return true;
	}

	// override page model and return search results pager
	@Override
	public WeblogEntriesPager getWeblogEntriesPager() {
		return pager;
	}

	// override page model and return search results pager
	@Override
	public WeblogEntriesPager getWeblogEntriesPager(String category) {
		return pager;
	}

	public String getTerm() {
		String query = searchRequest.getQuery();
        return (query == null)
			? "" : StringEscapeUtils.escapeXml10(query);
	}

	public String getRawTerm() {
		return (searchRequest.getQuery() == null) ? "" : searchRequest
				.getQuery();
	}

	/** The true total of {@link #getOwnHits}'s list. */
	public int getHits() {
		return ownTotal;
	}

	public int getOffset() {
		return offset;
	}

	/** How many results this page shows. */
	public int getLimit() {
		return ownHits.size();
	}

	/** The searched weblog is the site: the site space, or the aggregated front-page blog. */
	public boolean isSiteScope() {
		return siteScope;
	}

	/** Only this blog's matches, paged: {@code scope=blog} or a category. */
	public boolean isBlogOnly() {
		return blogOnly;
	}

	/** Blog view: this blog's top matches; paged views: the current page. */
	public List<SearchHit> getOwnHits() {
		return ownHits;
	}

	public int getOwnTotal() {
		return ownTotal;
	}

	/** Blog view only: the best matches from other blogs; empty otherwise. */
	public List<SearchHit> getOtherHits() {
		return otherHits;
	}

	public boolean isAnyWordFallback() {
		return anyWordFallback;
	}

	/** Site view: how many distinct blogs the hits fall in. */
	public int getBlogCount() {
		return perBlog.size();
	}

	/** Site view: that blog's other matches, beyond the one shown. */
	public int moreFromBlog(String handle) {
		return Math.max(0, perBlog.getOrDefault(handle, 0) - 1);
	}

	/** Blog view: this blog's matches alone, same words. */
	public String getShowAllUrl() {
		if (searchRequest.getQuery() == null) {
			return null;
		}
		return urlStrategy.getWeblogSearchURL(searchRequest.getWeblog(), searchRequest.getLocale(),
				searchRequest.getQuery(), null, 0, true, false);
	}

	/** Blog view: the site's search, same words; null with no query or no site space. */
	public String getSiteSearchUrl() {
		if (searchRequest.getQuery() == null) {
			return null;
		}
		try {
			Weblog site = weblogger.getWeblogManager().getSiteSpace();
			return site == null ? null : urlStrategy.getWeblogSearchURL(
					site, null, searchRequest.getQuery(), null, 0, false, true);
		} catch (WebloggerException e) {
			// a link, not a decision: the page simply omits it
			log.warn("Could not look up the site space for the site search link", e);
			return null;
		}
	}

	/** Site view: that blog's own matches, same words, absolute. */
	public String blogSearchUrl(WeblogWrapper blog) {
		if (blog == null || searchRequest.getQuery() == null) {
			return null;
		}
		return urlStrategy.getWeblogSearchURL(blog.getPojo(), null, searchRequest.getQuery(),
				null, 0, true, true);
	}

	public Set<String> getCategories() {
		return categories;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getWeblogCategoryName() {
		return searchRequest.getWeblogCategoryName();
	}

	@Override
	public WeblogCategoryWrapper getWeblogCategory() {
		if (searchRequest.getWeblogCategory() != null) {
			return WeblogCategoryWrapper.wrap(searchRequest.getWeblogCategory(), urlStrategy, weblogger);
		}
		return null;
	}

}

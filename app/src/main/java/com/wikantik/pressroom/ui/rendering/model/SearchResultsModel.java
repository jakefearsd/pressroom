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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.wrapper.WeblogPageWrapper;
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
	WeblogSearchRequest searchRequest;
	private URLStrategy urlStrategy;

	/** How many of this blog's own matches the blog view shows. */
	static final int OWN_IN_BLOG_VIEW = 8;
	/** How many other-blog matches the blog view shows. */
	static final int OTHERS_IN_BLOG_VIEW = 5;
	/** How many matching pages the Pages group shows. */
	static final int PAGES_IN_GROUP = 3;

	// the pager used by the 3.0+ rendering system
	private SearchResultsPager pager;

	private boolean siteScope;
	private boolean blogOnly;
	private List<SearchHit> ownHits = List.of();
	private int ownTotal;
	private List<SearchHit> otherHits = List.of();
	// each section says for itself whether it fell back to any of the words
	private boolean ownFallback;
	private boolean otherFallback;
	// the own (or only) list's hit count is a floor: more matched than were considered
	private boolean capped;
	private Map<String, Integer> perBlog = Map.of();
	// site view: "more from this blog" for each blog's first hit on this page
	private Map<SearchHit, Integer> moreByHit = Map.of();
	private int offset;
	private List<PageHit> pageHits = List.of();
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
				findPaged(indexMgr, handle);
			} else {
				findInBlogView(indexMgr, searched, handle);
			}
		} catch (WebloggerException | IllegalArgumentException we) {
			errorMessage = we.getMessage();
			forgetResults();
		}

		if (errorMessage.isEmpty()) {
			pageHits = findPageHits(indexMgr, handle);
		}

		// search completed, setup pager based on results
		pager = new SearchResultsPager(urlStrategy, searchRequest,
			paged && ownTotal > offset + RESULTS_PER_PAGE, offset / RESULTS_PER_PAGE);
	}

	/**
	 * One page of the site's or (scope=blog) one blog's results. The one
	 * place paging is worked out: the offset asked for, from the page number
	 * alone. The index clamps a page past its end by itself, so the model
	 * never second-guesses it from the total (which drops stale documents
	 * and so can disagree with what find returned).
	 */
	private void findPaged(IndexManager indexMgr, String handle) throws WebloggerException {
		offset = Math.max(0, searchRequest.getPageNum()) * RESULTS_PER_PAGE;
		SearchSpec.Scope scope = (siteScope && !blogOnly)
				? SearchSpec.Scope.SITE : SearchSpec.Scope.BLOG;
		SearchHits found = indexMgr.find(spec(scope, handle, offset, RESULTS_PER_PAGE), urlStrategy);
		takeOwn(found);
		perBlog = found.perBlog();
		if (scope == SearchSpec.Scope.SITE) {
			moreByHit = moreFromEachBlog(ownHits, perBlog);
		}
		// find answers a request past its end with the first page; show it as page 0
		if (offset > 0 && offset >= ownTotal && !ownHits.isEmpty()) {
			offset = 0;
		}
	}

	/**
	 * A blog's own view: its first results, and those of the other blogs
	 * unless the blog turned "more from the site" off. Never the site:
	 * isTheSite made its search the paged site view, so the site space never
	 * asks for "other" blogs.
	 */
	private void findInBlogView(IndexManager indexMgr, Weblog searched, String handle) throws WebloggerException {
		SearchHits own = indexMgr.find(
				spec(SearchSpec.Scope.BLOG, handle, 0, OWN_IN_BLOG_VIEW), urlStrategy);
		takeOwn(own);
		if (searched != null && !Boolean.FALSE.equals(searched.getShowMoreFromSite())) {
			SearchHits others = indexMgr.find(
					spec(SearchSpec.Scope.OTHER_BLOGS, handle, 0, OTHERS_IN_BLOG_VIEW), urlStrategy);
			otherHits = others.hits();
			otherFallback = others.anyWordFallback();
		}
	}

	/**
	 * The "Pages" group: the first results page only, never when the reader
	 * narrowed to a category (a page has none), at most three. The index is
	 * updated in the background, so it can still name a page that has been
	 * unpublished, set noindex or deleted since: each id is loaded and
	 * dropped unless the page is still PUBLISHED and indexable. This is a
	 * display path: a failure here shows no pages and never costs the post
	 * results.
	 */
	private List<PageHit> findPageHits(IndexManager indexMgr, String handle) {
		String category = searchRequest.getWeblogCategoryName();
		if (searchRequest.getPageNum() > 0 || (category != null && !category.isBlank())) {
			return List.of();
		}
		SearchSpec.Scope scope = (siteScope && !blogOnly) ? SearchSpec.Scope.SITE : SearchSpec.Scope.BLOG;
		try {
			List<String> ids = indexMgr.findPages(spec(scope, handle, 0, PAGES_IN_GROUP), PAGES_IN_GROUP * 2);
			List<PageHit> found = new ArrayList<>();
			for (String id : ids) {
				WeblogPage page = weblogger.getWeblogPageManager().getPage(id);
				if (page == null || page.getStatus() != WeblogPage.PubStatus.PUBLISHED
						|| Boolean.TRUE.equals(page.getNoindex())) {
					continue;
				}
				found.add(new PageHit(WeblogPageWrapper.wrap(page, urlStrategy, weblogger), snippetOf(page)));
				if (found.size() == PAGES_IN_GROUP) {
					break;
				}
			}
			return found;
		} catch (WebloggerException | IllegalArgumentException | IllegalStateException e) {
			log.warn("Could not find the pages matching \"{}\"; showing none", searchRequest.getQuery(), e);
			return List.of();
		}
	}

	/** The page's own search description, else the opening sentences of its content; plain text. */
	private String snippetOf(WeblogPage page) {
		String description = page.getSearchDescription();
		if (description != null && !description.isBlank()) {
			return description;
		}
		return WeblogPageWrapper.wrap(page, urlStrategy, weblogger).getExcerpt();
	}

	/** A search that failed shows no results, only its message. */
	private void forgetResults() {
		ownHits = List.of();
		otherHits = List.of();
		ownTotal = 0;
		ownFallback = false;
		otherFallback = false;
		capped = false;
		perBlog = Map.of();
		moreByHit = Map.of();
		categories = new TreeSet<>();
		offset = 0;
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
		ownFallback = found.anyWordFallback();
		capped = found.capped();
		categories = found.categories();
	}

	/**
	 * For each blog's first hit in {@code hits}, how many more of that blog's
	 * posts matched; its later hits on the page get nothing, so "K more from
	 * this blog" is said once per blog (final review, B5).
	 */
	private static Map<SearchHit, Integer> moreFromEachBlog(List<SearchHit> hits, Map<String, Integer> perBlog) {
		Map<SearchHit, Integer> more = new IdentityHashMap<>();
		Set<String> seen = new HashSet<>();
		for (SearchHit hit : hits) {
			WeblogWrapper blog = hit.entry().getWebsite();
			String handle = blog == null ? null : blog.getHandle();
			if (handle != null && seen.add(handle)) {
				more.put(hit, Math.max(0, perBlog.getOrDefault(handle, 0) - 1));
			}
		}
		return more;
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

	/**
	 * True when this blog's section (the blog view) or the one list (the site
	 * and paged views) matched only some of the words.
	 */
	public boolean isOwnFallback() {
		return ownFallback;
	}

	/** Blog view: true when the other blogs' section matched only some of the words. */
	public boolean isOtherFallback() {
		return otherFallback;
	}

	/**
	 * True when more posts matched than the 500 considered, so {@link #getHits}
	 * is a floor and the page says "more than" (final review, B4). The other
	 * blogs' section shows no count, so only this one is carried.
	 */
	public boolean isCapped() {
		return capped;
	}

	/** Site view: how many distinct blogs the hits fall in. */
	public int getBlogCount() {
		return perBlog.size();
	}

	/**
	 * Site view: that blog's other matches, beyond the one shown, on the
	 * blog's first hit on this page; 0 on its later hits, so it is said once.
	 */
	public int moreFor(SearchHit hit) {
		return moreByHit.getOrDefault(hit, 0);
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

	/** The matching pages, at most three, shown above the posts; empty when none apply. */
	public List<PageHit> getPageHits() {
		return pageHits;
	}

	/** A page hit's reader address, absolute (a site-wide hit may belong to another blog). */
	public String pageUrl(PageHit hit) {
		WeblogPage page = hit.page().getPojo();
		return urlStrategy.getWeblogStaticPageURL(page.getWeblog(), page, true);
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

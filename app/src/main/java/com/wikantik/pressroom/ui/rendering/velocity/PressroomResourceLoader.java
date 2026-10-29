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
package com.wikantik.pressroom.ui.rendering.velocity;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.TemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.resource.Resource;
import org.apache.velocity.runtime.resource.loader.ResourceLoader;
import org.apache.velocity.util.ExtProperties;

/**
 * The PressroomResourceLoader is a Velocity template loader which loads templates
 * from custom themes.
 * 
 * The loader is instantiated by Velocity from {@code velocity.properties}, so
 * it cannot be constructor-injected; it takes the {@link Weblogger} from the
 * engine's application attributes, set by {@link PressroomVelocity#initialize}.
 * 
 * @author <a href="mailto:lance@brainopolis.com">Lance Lavandowska</a>
 * @version $Id: PressroomResourceLoader.java,v 1.9 2005/01/15 03:32:49 snoopdave
 *          Exp $
 */
public class PressroomResourceLoader extends ResourceLoader {

	private static final Logger log = LoggerFactory.getLogger(PressroomResourceLoader.class);

	private Weblogger weblogger;

	/**
	 * Velocity calls {@code commonInit} (which sets {@code rsvc}) and then this.
	 *
	 * @throws IllegalStateException if the engine carries no {@link Weblogger}
	 *         -- i.e. it was not built by {@link PressroomVelocity#initialize}
	 */
    @Override
	public void init(ExtProperties configuration) {
		log.debug("{}", configuration);
		this.weblogger = facadeFrom(rsvc);
	}

	static Weblogger facadeFrom(org.apache.velocity.runtime.RuntimeServices rsvc) {
		Object attribute = rsvc == null ? null : rsvc.getApplicationAttribute(PressroomVelocity.WEBLOGGER_ATTRIBUTE);
		if (!(attribute instanceof Weblogger)) {
			throw new IllegalStateException("Velocity engine carries no " + PressroomVelocity.WEBLOGGER_ATTRIBUTE
					+ " application attribute; build it through PressroomVelocity.initialize(...)");
		}
		return (Weblogger) attribute;
	}

	/**
	 * Get an Reader so that the Runtime can build a template with it.
	 * 
	 * @param name
	 *            name of template
	 * @return Reader containing template
     * @throws ResourceNotFoundException
	 */
    @Override
    public Reader getResourceReader(String name, String encoding) {

		log.debug("Looking for: {}", name);

		if (name == null || name.length() == 0) {
			throw new ResourceNotFoundException(
					"Need to specify a template name!");
		}

		// theme template names are <template>|<renditionType> (e.g., mytemplate|standard)
		RenditionType renditionType = RenditionType.STANDARD;
		if (name.contains("|")) {
			String[] pair = name.split("\\|");
			name = pair[0];
			renditionType = RenditionType.valueOf(pair[1].toUpperCase(Locale.ROOT));
		}

		log.debug("   Actually, it's {}", name);

		try {
			WeblogTemplate page = weblogger.getWeblogManager().getTemplate(name);

			if (page == null) {
				throw new ResourceNotFoundException(
						"PressroomResourceLoader: page \"" + name + "\" not found");
			}
			String contents = "";
			TemplateRendition templateCode = page.getTemplateRendition(renditionType);
			if (templateCode != null) {
				contents = templateCode.getTemplate();
			}
			// Velocity passes a NULL encoding from resourceExists(); see the same
			// guard in ThemeResourceLoader, where an unguarded getBytes(null)
			// produced intermittent 404s the moment caching was switched on. This
			// loader still runs uncached, so the path is not reachable today -- the
			// guard is here so that stays a choice rather than a trap.
			String charset = (encoding == null || encoding.isEmpty())
					? StandardCharsets.UTF_8.name() : encoding;
			return new InputStreamReader(new ByteArrayInputStream(contents.getBytes(charset)), charset);

		} catch (UnsupportedEncodingException uex) {
			// This should never actually happen. We expect UTF-8 in all JRE
			// installation.
//			log.error(uex);
			throw new RuntimeException(uex);

		} catch (WebloggerException | ResourceNotFoundException re) {
			String msg = "PressroomResourceLoader Error: "
					+ "database problem trying to load resource " + name;
//			log.error(msg, re);
			throw new ResourceNotFoundException(msg, re);
		}
	}

	/**
	 * Always false: this loader has no timestamp to compare against, because
	 * a custom template is a database row reached by name and nothing here
	 * records when it last changed.
	 *
	 * <p><b>This answer is what forces {@code resource.loader.roller.cache}
	 * to stay false in velocity.properties, and the two may only ever change
	 * together.</b> A constant false means "never stale", so with caching on
	 * Velocity would reuse the first parse tree it built for the life of the
	 * JVM and editing a custom template would silently do nothing until a
	 * restart. {@code ThemeResourceLoader} told the same lie and had the same
	 * cache restriction until it was taught to report the theme's real disk
	 * timestamp; enabling this cache means doing that work here first.
	 * {@code LoaderCachingContractTest} fails the build if the pairing breaks.
	 * 
	 * @see org.apache.velocity.runtime.resource.loader.ResourceLoader#isSourceModified(org.apache.velocity.runtime.resource.Resource)
	 */
    @Override
	public boolean isSourceModified(Resource resource) {
		return false;
	}

	/**
	 * Always 0, i.e. "no timestamp available"; see
	 * {@link #isSourceModified(Resource)} for why that constrains caching.
	 * 
	 * @see org.apache.velocity.runtime.resource.loader.ResourceLoader#getLastModified(org.apache.velocity.runtime.resource.Resource)
	 */
    @Override
	public long getLastModified(Resource resource) {
		return 0;
	}

}

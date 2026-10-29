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

package com.wikantik.pressroom.ui.rendering.util.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.Job;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.StaticTemplate;
import com.wikantik.pressroom.pojos.Template;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.ui.rendering.Renderer;
import com.wikantik.pressroom.ui.rendering.RendererManager;
import com.wikantik.pressroom.ui.rendering.model.ModelLoader;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.util.cache.CachedContent;


/**
 * EXPERIMENTAL!!
 *
 * A job which will "warm up" some of the rendering layer caches by iterating
 * over all weblogs in the system and rendering a set of their content to put
 * in the caches for later use.
 *
 * Currently only supports warming up the feed cache.
 */
public class WeblogCacheWarmupJob implements Job {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogCacheWarmupJob.class);
    
    // inputs from the user
    private Map<String, Object> inputs = null;

    /** The business-tier facade handed to every model the job loads. */
    private final Weblogger weblogger;


    /**
     * @param weblogger the facade the rendering models receive through
     *                  {@code initData} (the models are reflectively
     *                  instantiated and have no other way to get it); its
     *                  url strategy is passed alongside, since the models
     *                  no longer fall back to the application's default.
     */
    public WeblogCacheWarmupJob(Weblogger weblogger) {
        this.weblogger = weblogger;
    }


    @Override
    public void execute() {
        
        log.debug("starting");
        
        // check inputs to see what work we are going to do
        if(inputs != null) {
            
            // what weblogs will we handle?
            @SuppressWarnings("unchecked")
            List<String> weblogs = (List<String>) inputs.get("weblogs");
            if(weblogs == null) {
                return;
            }
            
            // should we do atom entries feeds?
            if("true".equals(inputs.get("feed-entries-atom"))) {
                this.warmupFeedCache(weblogs, "entries", "atom");
            }
        }
        
        log.debug("finished");
    }
    
    
    @Override
    public Map<String, Object> output() {
       return Map.of();
    }
    
    
    @Override
    public void input(Map<String, Object> input) {
        this.inputs = input;
    }
    
    
    private void warmupFeedCache(List<String> weblogs, String type, String format) {
        
        if(weblogs == null) {
            return;
        }
        
        // we are working on the feed cache
        WeblogFeedCache feedCache = WeblogFeedCache.getInstance();
        long start = System.currentTimeMillis();
        
        for (String weblogHandle : weblogs) {
            log.debug("doing weblog {}", weblogHandle);
            
            try {
                // we need a feed request to represent the data
                WeblogFeedRequest feedRequest = new WeblogFeedRequest(weblogger);
                feedRequest.setWeblogHandle(weblogHandle);
                feedRequest.setType(type);
                feedRequest.setFormat(format);
                
                
                // populate the rendering model
                Map<String, Object> modelMap = new HashMap<>();
                Map<String, Object> initData = new HashMap<>();
                initData.put("request", null);
                initData.put("feedRequest", feedRequest);
                initData.put("weblogRequest", feedRequest);
                // every model requires both; this was the one caller that
                // supplied neither and leaned on the models' (now deleted)
                // fallback to the static locator
                initData.put("weblogger", weblogger);
                initData.put("urlStrategy", weblogger.getUrlStrategy());
                
                // Load models for feeds
                String feedModels = WebloggerConfig.getProperty("rendering.feedModels");
                ModelLoader.loadModels(feedModels, modelMap, initData, true);
                
                // TODO: re-enable custom models when they are actually used
                // Load weblog custom models
                //ModelLoader.loadCustomModels(weblog, model, initData);
                
                
                // lookup Renderer we are going to use
                Renderer renderer;
                Template template = new StaticTemplate(
					"weblog-"+type+"-"+format+".vm", TemplateLanguage.VELOCITY);
                renderer = RendererManager.getRenderer(template);
                
                
                // render content.  use default size of about 24K for a standard page
                CachedContent rendererOutput = new CachedContent(PressroomConstants.TWENTYFOUR_KB_IN_BYTES);
                // closed even if render() threw, or the buffer's underlying
                // stream/writer leaks on that path
                try (rendererOutput) {
                    renderer.render(modelMap, rendererOutput.getCachedWriter());
                    // flush() captures the bytes; it must precede the close
                    rendererOutput.flush();
                }

                // now just put it in the cache
                String key = feedCache.generateKey(feedRequest);
                feedCache.put(key, rendererOutput);
                
            } catch(Exception e) {
                // bummer, error during rendering
                log.error("Error rendering for weblog {}", weblogHandle, e);
            }
        }
        
        long end = System.currentTimeMillis();
        // elapsed milliseconds reported in seconds; this used to multiply
        // rather than divide, so a warmup that took 2 seconds was logged as
        // having taken 2,000,000
        long time = (end-start) / PressroomConstants.SEC_IN_MS;

        log.info("Completed warmup for {}/{} in {} secs.", type, format, time);
        
    }
    
}

package com.wikantik.pressroom.ui.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.util.cache.Cache;
import com.wikantik.pressroom.util.cache.CacheHandler;
import com.wikantik.pressroom.util.cache.CacheManager;

import java.util.HashMap;
import java.util.Map;

public class PressroomLoginSessionManager {
   private static final Logger log = LoggerFactory.getLogger(PressroomLoginSessionManager.class);
   private static final String CACHE_ID = "roller.session.cache";
   private final Cache sessionCache;

   public static PressroomLoginSessionManager getInstance() {
      return PressroomLoginSessionManager.SingletonHolder.INSTANCE;
   }

   private static final class SingletonHolder {
      private static final PressroomLoginSessionManager INSTANCE = new PressroomLoginSessionManager();
   }

   class SessionCacheHandler implements CacheHandler {
     @Override
     public void invalidate(User user) {
         if (user != null && user.getUserName() != null) {
            sessionCache.remove(user.getUserName());
         }
      }
   }

   /** Testing purpose only */
   PressroomLoginSessionManager(Cache cache) {
      this.sessionCache = cache;
      CacheManager.registerHandler(new SessionCacheHandler());
   }

   private PressroomLoginSessionManager() {
      Map<String, String> cacheProps = new HashMap<>();
      cacheProps.put("id", CACHE_ID);
      cacheProps.put("size", "1000");  // Cache up to 1000 sessions
      cacheProps.put("timeout", "3600"); // Session timeout in seconds (1 hour)
      this.sessionCache = CacheManager.constructCache(null, cacheProps);
      CacheManager.registerHandler(new SessionCacheHandler());
   }

   public void register(String userName, PressroomSession session) {
      if (userName != null && session != null) {
         this.sessionCache.put(userName, session);
         log.debug("Registered session for user: {}", userName);
      }
   }

   public PressroomSession get(String userName) {
      if (userName != null) {
         return (PressroomSession) this.sessionCache.get(userName);
      }
      return null;
   }

   public void invalidate(String userName) {
      if (userName != null) {
         this.sessionCache.remove(userName);
         log.debug("Invalidated session for user: {}", userName);
      }
   }
}

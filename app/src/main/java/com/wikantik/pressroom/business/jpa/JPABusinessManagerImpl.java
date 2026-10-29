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
package com.wikantik.pressroom.business.jpa;

import java.sql.Timestamp;
import java.util.List;

import jakarta.persistence.TypedQuery;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA implementation of {@link BusinessManager}.
 */
public class JPABusinessManagerImpl implements BusinessManager {

    private static final Logger log =
            LoggerFactory.getLogger(JPABusinessManagerImpl.class);

    private final JPAPersistenceStrategy strategy;

    protected JPABusinessManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Business Manager");
        this.strategy = strategy;
    }

    @Override
    public List<Business> getBusinesses() throws WebloggerException {
        return strategy.getNamedQuery("Business.getAll", Business.class)
                .getResultList();
    }

    @Override
    public Business getBusiness(String id) throws WebloggerException {
        if (id == null) {
            return null;
        }
        return (Business) strategy.load(Business.class, id);
    }

    @Override
    public void saveBusiness(Business business) throws WebloggerException {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (business.getCreated() == null) {
            business.setCreated(now);
        }
        business.setLastModified(now);
        strategy.store(business);

        // Rendered pages expire only through weblog.lastModified (spec,
        // "Cache expiry"). A JPQL bulk UPDATE would write the column but
        // bypass EclipseLink's shared cache, leaving stale Weblog instances
        // whose old lastModified keeps serving stale pages. So load the
        // users and set the field on the managed instances instead; the
        // flush writes the rows and the cache stays coherent (10-50 blogs).
        for (Weblog weblog : getWeblogsUsing(business)) {
            weblog.touchLastModified();
            strategy.store(weblog);
        }
    }

    @Override
    public void removeBusiness(Business business) throws WebloggerException {
        long using = countWeblogsUsing(business);
        if (using > 0) {
            throw new WebloggerException("business in use by " + using + " weblogs");
        }
        strategy.remove(business);
    }

    @Override
    public long countWeblogsUsing(Business business) throws WebloggerException {
        TypedQuery<Long> query = strategy.getNamedQueryCommitFirst(
                "Weblog.countByBusiness", Long.class);
        query.setParameter(1, business);
        return query.getSingleResult();
    }

    @Override
    public List<Weblog> getWeblogsUsing(Business business) throws WebloggerException {
        TypedQuery<Weblog> query = strategy.getNamedQueryCommitFirst(
                "Weblog.getByBusiness", Weblog.class);
        query.setParameter(1, business);
        return query.getResultList();
    }
}

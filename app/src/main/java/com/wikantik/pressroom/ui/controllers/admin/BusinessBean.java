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
package com.wikantik.pressroom.ui.controllers.admin;

import com.wikantik.pressroom.pojos.Business;

/**
 * Form bean for the Businesses edit screen. Every field is a raw string,
 * including the type: a forged value must reach the controller's validation
 * rather than fail binding.
 */
public class BusinessBean {

    private String id;
    private String name;
    private String businessType = Business.BusinessType.Organization.name();
    private String websiteUrl;
    private String bookingUrl;
    private String telephone;
    private String email;
    private String logoUrl;
    private String sameAs;
    private String areaServed;
    private String description;

    // CPD-OFF -- A pojo and its form bean necessarily name the same fields; see CreateUserBean.
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public String getBookingUrl() {
        return bookingUrl;
    }

    public void setBookingUrl(String bookingUrl) {
        this.bookingUrl = bookingUrl;
    }

    public String getTelephone() {
        return telephone;
    }

    public void setTelephone(String telephone) {
        this.telephone = telephone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getSameAs() {
        return sameAs;
    }

    public void setSameAs(String sameAs) {
        this.sameAs = sameAs;
    }

    public String getAreaServed() {
        return areaServed;
    }

    public void setAreaServed(String areaServed) {
        this.areaServed = areaServed;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** Fills the form from a stored record. */
    public void copyFrom(Business b) {
        id = b.getId();
        name = b.getName();
        businessType = b.getBusinessType() == null ? null : b.getBusinessType().name();
        websiteUrl = b.getWebsiteUrl();
        bookingUrl = b.getBookingUrl();
        telephone = b.getTelephone();
        email = b.getEmail();
        logoUrl = b.getLogoUrl();
        sameAs = b.getSameAs();
        areaServed = b.getAreaServed();
        description = b.getDescription();
    }
    // CPD-ON
}

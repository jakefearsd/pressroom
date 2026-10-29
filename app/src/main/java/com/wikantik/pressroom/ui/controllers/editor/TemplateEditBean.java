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

package com.wikantik.pressroom.ui.controllers.editor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.WeblogTemplate;


/**
 * Form bean for TemplateEdit action.
 */
public class TemplateEditBean {
    private static final Logger log = LoggerFactory.getLogger(TemplateEditBean.class);
 
    private String id = null;
    private String name = null;
    private ComponentType action = null;
    private String description = null;
    private String link = null;
    private String contentsStandard = null;
    private String templateLanguage = null;
    private boolean navbar= false;
    private boolean hidden = false;
    private Boolean autoContentType = Boolean.FALSE;
    private String manualContentType = null;
    private String type = null;

    // template ID of standard template version
    private String standardTemplateId = null;
    
    
    public String getId() {
        return this.id;
    }
    
    public void setId( String id ) {
        this.id = id;
    }
    
    public String getName() {
        return this.name;
    }
    
    public void setName( String name ) {
        this.name = name;
    }
    
	public void setAction(ComponentType action) {
		this.action = action;
	}

	public ComponentType getAction() {
		return action;
	}
    
    public String getDescription() {
        return this.description;
    }
    
    public void setDescription( String description ) {
        this.description = description;
    }

    public String getLink() {
        return this.link;
    }
    
    public void setLink( String link ) {
        this.link = link;
    }

    public String getContentsStandard() {
        return this.contentsStandard;
    }

    public void setContentsStandard( String contents ) {
        this.contentsStandard = contents;
    }

    public String getTemplateLanguage() {
        return this.templateLanguage;
    }
    
    public void setTemplateLanguage( String templateLanguage ) {
        this.templateLanguage = templateLanguage;
    }
    
    public boolean isNavbar() {
        return this.navbar;
    }
    
    public void setNavbar( boolean navbar ) {
        this.navbar = navbar;
    }
    
    public boolean isHidden() {
        return this.hidden;
    }
    
    public void setHidden( boolean hidden ) {
        this.hidden = hidden;
    }
    
    public Boolean getAutoContentType() {
        return autoContentType;
    }
    
    public void setAutoContentType(Boolean autoContentType) {
        this.autoContentType = autoContentType;
    }
    
    public String getManualContentType() {
        return manualContentType;
    }
    
    public void setManualContentType(String manualContentType) {
        this.manualContentType = manualContentType;
    }
    
    
    /**
     * Copies the form onto the template and its STANDARD rendition. Persists
     * nothing: the bean is pure (the data binder instantiates it), so the
     * controller's save path is what calls {@code saveTemplateRendition} on
     * the rendition this leaves behind -- reachable afterwards through
     * {@code dataHolder.getTemplateRendition(RenditionType.STANDARD)} in both
     * branches below, because the rendition constructor registers itself with
     * the template.
     */
    public void copyTo(WeblogTemplate dataHolder) throws WebloggerException {

        if (dataHolder.getTemplateRendition(RenditionType.STANDARD) != null) {
            // if we have a template, then set it
            CustomTemplateRendition tc = dataHolder.getTemplateRendition(RenditionType.STANDARD);
            tc.setTemplate(contentsStandard);
        } else {
            // otherwise create it, then set it -- with the edited contents, not
            // an empty string. Writing "" here silently threw away everything
            // the user had typed whenever the template had no STANDARD
            // rendition yet, and still reported the save as successful. The
            // column is NOT NULL, hence the fallback for an absent field.
            CustomTemplateRendition tc = new CustomTemplateRendition(dataHolder, RenditionType.STANDARD);
            tc.setTemplate(contentsStandard == null ? "" : contentsStandard);
        }

        // the rest of the template properties can be modified only when
        // dealing with a CUSTOM weblog template
        if (dataHolder.isCustom()) {
            dataHolder.setName(getName());
            dataHolder.setAction(getAction());
            dataHolder.setDescription(getDescription());
            dataHolder.setLink(getLink());
            dataHolder.setNavbar(isNavbar());
            dataHolder.setHidden(isHidden());
        }
    }
    
    
    public void copyFrom(WeblogTemplate dataHolder) throws WebloggerException {
        this.id = dataHolder.getId();
        this.name = dataHolder.getName();
        this.action = dataHolder.getAction();
        this.description = dataHolder.getDescription();
        this.link = dataHolder.getLink();

        if (dataHolder.getTemplateRendition(RenditionType.STANDARD) != null) {
            this.contentsStandard = dataHolder.getTemplateRendition(RenditionType.STANDARD).getTemplate();
        } else {
            this.contentsStandard = "";
        }
		log.debug("Standard: {}", this.contentsStandard);

        this.navbar = dataHolder.isNavbar();
        this.hidden = dataHolder.isHidden();
        setManualContentType(dataHolder.getOutputContentType());
        if(getManualContentType() != null) {
            setAutoContentType(Boolean.FALSE);
        }
    }

    public String getStandardTemplateId() {
        return standardTemplateId;
    }

    public void setStandardTemplateId(String standardTemplateId) {
        this.standardTemplateId = standardTemplateId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

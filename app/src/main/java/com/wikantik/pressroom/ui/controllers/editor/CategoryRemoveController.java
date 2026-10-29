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

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Remove a category.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class CategoryRemoveController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(CategoryRemoveController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "categoryRemove";
    }

    @Override
    public String getPageTitle() {
        return "categoryDeleteOK.title";
    }


    @PostMapping("/categoryRemove!remove.rol")
    public String remove(HttpServletRequest request, Model model,
                         @RequestParam(value = "removeId", required = false) String removeId,
                         @RequestParam(value = "targetCategoryId", required = false) String targetCategoryId,
                         RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);

        // Both ids are client input and the permission interceptor only vouches
        // for the action weblog. Resolving either globally let an editor delete
        // another weblog's category, or -- worse, because it is silent -- name a
        // foreign category as the destination and re-file somebody else's posts
        // into it.
        WeblogCategory category = lookupCategory(removeId, request);
        if (category != null) {
            try {
                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();

                // removeWeblogCategory refuses this too -- that refusal is the
                // real enforcement -- but it arrives as a bare WebloggerException
                // the catch below can only report as "check the logs". A weblog
                // needing its last category is an ordinary thing to tell someone,
                // not a server fault, so say it plainly here.
                if (getActionWeblog(request).getWeblogCategories().size() <= 1) {
                    addFlashError(redirectAttributes, "categoryForm.error.lastCategory", request);
                    return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                            + getActionWeblog(request).getHandle();
                }

                if (targetCategoryId != null) {
                    WeblogCategory target = lookupCategory(targetCategoryId, request);
                    if (target == null) {
                        addFlashError(redirectAttributes, "categoryForm.error.notFound", request);
                        return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                                + getActionWeblog(request).getHandle();
                    }
                    wmgr.moveWeblogCategoryContents(category, target);
                    weblogger.flush();
                }

                CacheManager.invalidate(category);
                wmgr.removeWeblogCategory(category);
                weblogger.flush();

                addFlashMessage(redirectAttributes, "categoryForm.removed", category.getName(), request);

                return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
            } catch (Exception ex) {
                log.error("Error removing category - {}", removeId, ex);
                addFlashError(redirectAttributes, "generic.error.check.logs", request);
            }
        } else {
            addFlashError(redirectAttributes, "categoryForm.error.notFound", request);
        }

        // Removal is driven by a modal on the category list, so there is no
        // confirmation page to redisplay; return to the list with the error.
        return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                + getActionWeblog(request).getHandle();
    }

}

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

package com.wikantik.pressroom.util;

import org.jdom2.input.SAXBuilder;

/**
 * The one way this application builds an XML parser.
 *
 * <p>A bare {@code new SAXBuilder()} resolves DOCTYPEs and external entities,
 * which is XXE: a document can name {@code file:///etc/passwd} (or an http
 * URL, making the server an SSRF proxy) and have the contents pulled into the
 * parse. Every SAXBuilder in Roller goes through here so that the safe
 * configuration is a property of the codebase rather than of whoever wrote
 * the most recent call site.
 *
 * <p><strong>None of Roller's three callers parses attacker-controlled XML
 * today</strong> -- the runtime config defs and admin menus are classpath
 * resources, and {@code theme.xml} ships in the themes directory -- so this
 * closes no live hole. It exists because "safe only because of where the
 * input happens to come from" is a property that quietly stops holding: a
 * user-uploadable theme bundle would make all three reachable, and the
 * failure would be silent. Cheaper to hold the invariant than to remember to
 * re-audit it.
 *
 * <p>DOCTYPEs are disabled outright rather than merely restricting entity
 * resolution, which is the stronger and simpler control. That is affordable
 * only because no shipped file declares one; {@code SafeXmlTest} records the
 * check, and a future file that needs a DOCTYPE is a deliberate decision
 * rather than a reason to loosen this.
 */
public final class SafeXml {

    /** Not instantiable. */
    private SafeXml() {
    }

    /**
     * A {@link SAXBuilder} that refuses DOCTYPEs and never resolves external
     * entities.
     */
    public static SAXBuilder saxBuilder() {
        SAXBuilder builder = new SAXBuilder();
        // Refusing the DOCTYPE outright is what actually stops XXE: with no
        // DTD there is nowhere to declare an entity in the first place.
        builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        // Defence in depth, in case a parser implementation honours the
        // features below but not the one above.
        builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
        builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        builder.setExpandEntities(false);
        return builder;
    }
}

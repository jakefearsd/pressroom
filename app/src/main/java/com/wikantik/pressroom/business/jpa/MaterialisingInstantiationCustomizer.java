/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import com.wikantik.pressroom.pojos.EntityIds;
import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.sessions.Session;
import org.eclipse.persistence.sessions.SessionCustomizer;

/**
 * EclipseLink session customizer ({@code eclipselink.session.customizer})
 * that makes EclipseLink build entities inside {@link EntityIds#materialise},
 * so the objects it creates to hold loaded rows, and its clones, skip the
 * random UUID their field initialiser would otherwise draw.
 *
 * <p>Safe because every mapping uses PROPERTY access: EclipseLink sets the
 * stored id through {@code setId} on every object it builds this way, and an
 * object it persists was made by the application with {@code new}, which
 * still generates the id at construction.
 *
 * <p>EclipseLink instantiates this reflectively, so it needs the public
 * no-argument constructor.
 */
public class MaterialisingInstantiationCustomizer implements SessionCustomizer {

    @Override
    public void customize(Session session) {
        for (ClassDescriptor descriptor : session.getDescriptors().values()) {
            Constructor<?> constructor = publicNoArgConstructor(descriptor.getJavaClass());
            // Anything else keeps EclipseLink's default instantiation.
            if (constructor != null) {
                descriptor.useFactoryInstantiationPolicy(new Factory(constructor), "newInstance");
            }
        }
    }

    static Constructor<?> publicNoArgConstructor(Class<?> type) {
        int modifiers = type.getModifiers();
        if (Modifier.isAbstract(modifiers) || !Modifier.isPublic(modifiers)) {
            return null;
        }
        try {
            return type.getConstructor();
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Builds one entity class through its public no-argument constructor with
     * id generation suspended. EclipseLink calls {@link #newInstance}
     * reflectively.
     */
    public static final class Factory {

        private final Constructor<?> constructor;

        Factory(Constructor<?> constructor) {
            this.constructor = constructor;
        }

        public Object newInstance() {
            return EntityIds.materialise(() -> {
                try {
                    return constructor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Could not build " + constructor.getDeclaringClass().getName(), e);
                }
            });
        }
    }
}

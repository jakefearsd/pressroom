package com.wikantik.pressroom.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a test class that uses {@link UmamiContainer}, so {@link UmamiWarmUp} starts it early. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UsesUmami {
}

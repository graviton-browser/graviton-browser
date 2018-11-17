package app.graviton.api.v1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation defines which version of Graviton this app *targets*. Target versioning is a scheme that allows
 * apps to declare what version of a platform they were tested against. This allows that platform to
 * switch on or off behavioural changes that might risk being backwards-incompatible, depending on whether or not
 * the app can tolerate them. By declaring a target version that's greater than 1 (the default), your app can indicate
 * that it's not necessary to engage backwards compatibility logic that may impact performance, usability, security etc.<p>
 *
 * The best way to use this annotation is to always add it to your main class (the class defined in your manifest as
 * the startup class). Set the version to whatever version of Graviton you tested your app with. Then when you re-test
 * your app on a later version, change this number to match before starting your testing. If it's all OK you know
 * your app is compatible. Changes linked to the target version will be published on the Graviton website.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface GravitonTarget {
    int version() default 1;
}
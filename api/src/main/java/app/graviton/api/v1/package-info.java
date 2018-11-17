/**
 * Interfaces and types in this package allow you to access APIs that only appear when run inside the Graviton app
 * browser. This module is intentionally kept small and simple with no implementation code in it, so it's perfectly
 * OK to depend on it from apps that'll be primarily used outside of Graviton. <p>
 *
 * Whilst this package contains types from JavaFX, you don't need to have JavaFX on the classpath or module path
 * at runtime if you are writing some other kind of app: simply avoiding using those types is sufficient because
 * the JVM does late binding on everything.
 */
package app.graviton.api.v1;
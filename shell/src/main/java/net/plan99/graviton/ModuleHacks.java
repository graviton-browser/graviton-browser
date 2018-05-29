package net.plan99.graviton;

import com.sun.javafx.application.ParametersImpl;
import javafx.application.Application;

/**
 * Workaround for lack of ability to specify --add-opens equivalent in Kotlin. For Java 9+, starting a JavaFX Application after one has
 * already been started requires access to an internal class, which requires us to override the module system. But that can't be done
 * from Kotlin, so we use a Java class to do it instead.
 */
public class ModuleHacks {
    public static void setParams(Application application, String[] args) {
        ParametersImpl.registerParameters(application, new ParametersImpl(args));
    }
}

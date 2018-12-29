package app.graviton;

import com.sun.javafx.application.ParametersImpl;
import javafx.application.Application;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Workaround for lack of ability to specify --add-opens equivalent in Kotlin. For Java 9+, starting a JavaFX Application after one has
 * already been started requires access to an internal class, which requires us to override the module system. But that can't be done
 * from Kotlin, so we use a Java class to do it instead.
 */
public class ModuleHacks {
    public static void setParams(Application application, String[] args) {
        ParametersImpl.registerParameters(application, new ParametersImpl(args));
    }

    public static void removeParams(Application application) {
        try {
            Field params = ParametersImpl.class.getDeclaredField("params");
            params.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Application, Application.Parameters> map = (Map<Application, Application.Parameters>) params.get(null);
            map.remove(application);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Ignore, probably JavaFX version upgrade.
        }
    }
}

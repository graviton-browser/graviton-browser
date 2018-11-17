package app.graviton.api.v1;

import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Implementing this interface on your main class (as configured in your manifest) lets you re-use the top level Graviton
 * shell window, with a smooth transition.<p>
 *
 * Simply return your intended top level {@link javafx.scene.Scene scene} from {@link #createScene(Graviton)}.
 * This method will be called *before* {@link javafx.application.Application#start(Stage)}, so you should move any
 * initialisation code you need into this method instead. You will still receive a call to
 * {@link javafx.application.Application#start(Stage)} however, the stage is already configured with a new title, etc.
 * You should therefore change your start method to leave the stage well alone if it's already visible, as this
 * indicates Graviton configured it for you. In this way you can write an app that behaves well whether it's run inside
 * or outside the shell.<p>
 *
 * You will receive a {@link Graviton} object that will let you interrogate your environment and access various features
 * exposed by the browser. There's no other way to get to it at the moment so stash a reference to it somewhere safe.<p>
 *
 * You don't have to write a JavaFX app to benefit from this feature. For example, a Swing app could implement this
 * interface on its main class and then return a scene containing a {@link javafx.embed.swing.SwingNode} to benefit
 * in the same way.<p>
 *
 * Be aware:<p>
 *
 * <ul>
 * <li>Implementing this interface will mean your main method will not be called. Do not do any important work in main()
 *     if you wish to use this feature.</li>
 * <li>Don't mess with the JVM process, like by calling {@link System#exit(int)}. In future these sorts of operations
 *     may be blocked.</li>
 * <li>This is all ignored if the user runs the app from the command line. In that case a main method is required.</li>
 * </ul>
 *
 * Here's an example of how you might adapt a typical JavaFX application:<p>
 *
 * <pre>
 * {@code
 * public class MyApp extends Application implements GravitonRunInShell {
 *     public Scene createScene(Graviton graviton, double width, double height) {
 *         Button root = new Button("Hello world!");
 *         if (graviton != null)
 *             return new Scene(root, graviton.getWidth(), graviton.getHeight());   // Fill the browser area.
 *         else
 *             return new Scene(root);
 *     }
 *
 *     public void start(Stage primaryStage) {
 *         primaryStage.setTitle("My App");
 *         if (primaryStage.isShowing()) return;   // Running in Graviton so bail out.
 *
 *         // Running outside of Graviton, set up the stage.
 *         primaryStage.setScene(createScene(null));
 *         primaryStage.show();
 *     }
 * }
 * }
 * </pre>
 */
public interface GravitonRunInShell {
    /**
     * Called after your main class is created. The returned scene will replace the Graviton GUI, possibly with an
     * animation. See the main interface docs for more information.
     *
     * @param graviton A reference to a control interface for the browser.
     */
    Scene createScene(Graviton graviton);
}

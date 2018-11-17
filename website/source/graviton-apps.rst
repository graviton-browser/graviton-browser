Graviton apps
*************

Graviton can run any JVM app with a main method or subclass of ``javafx.application.Application``. However, apps can
opt-in to new features only available when running inside Graviton by using the API.

.. raw:: html

   <a href="_static/api/index.html"><button class="button button2">Read the Graviton API documentation</button></a><br><br>

To use the API add a dependency on the ``app.graviton:graviton-api`` library.
This provides various types that can be used to opt-in to features and communicate with Graviton. Although this will
become a runtime dependency, it's very small and adds no overhead when Graviton is not in use.

Testing your app locally
------------------------

To verify your app runs well in Graviton, you can publish to a special local Maven repository called ``dev-local``
that is examined by Graviton as if it were a remote repository. It exists in your ``$HOME/.m2/dev-local`` directory,
or Windows equivalent.

Here's how you would configure Gradle to publish to this repository:


.. sourcecode:: groovy

   publishing {
       publications {
           app(MavenPublication) {
               from components.java
           }
       }
       repositories {
           maven {
               name = 'gravitonLocal'
               def homePath = System.properties['user.home']
               url = "file://${homePath}/.m2/dev-local"
           }
       }
   }

If you add this snippet to the top level of your Gradle file, you can now run ``gradle publishAppPublicationToGravitonLocalRepository``
(you can also use the IntelliJ GUI to do this, as it's a bit of a mouthful) and the app will become visible to Graviton.

Set the main class in your app
------------------------------

You need to start by making sure your JAR is executable. This involves specifying the main class in your manifest.

Here's how you do it with Maven::

   <plugin>
       <groupId>org.apache.maven.plugins</groupId>
       <artifactId>maven-jar-plugin</artifactId>
       <version>2.6</version>
       <configuration>
           <archive>
               <manifest>
                   <mainClass>tictactoe.TicTacToe</mainClass>
               </manifest>
           </archive>
       </configuration>
   </plugin>

Or for Gradle::

   jar {
       manifest {
           attributes("Main-Class": "tictactoe.TicTacToe")
       }
   }

This class should contain your static main method.

Reusing the top level frame
---------------------------

Currently there is only one special feature available: you can opt in to re-using the top level window that the GUI
creates for you. This avoids a jarring transition as Graviton hides its shell window and starts your own app.
Instead the contents of the shell are smoothly replaced by your own app's content. To do this:

1. Implement a JavaFX app by subclassing ``javafx.application.Application`` as normal. Set this to be your
   main class in your application manifest as above.
2. Implement the ``GravitonRunInShell`` interface on your main class. It requires one method ``createScene``, which
   takes a ``Graviton`` object and returns a JavaFX ``Scene``.
3. Refactor your ``start(Stage)`` method so the part that configures your ``Scene`` is moved into the ``createScene``
   method. From ``start`` just pass null to the parameter.
4. Adjust your ``start`` method so if the stage is already visible, you don't attempt to set the scene or modify the
   window in other ways beyond adjusting the title.
5. Adjust your ``createScene`` method so if the Graviton parameter is non-null, you pass the width and height obtainable
   via that object into the ``Scene`` constructor (assuming you want your scene to fill the whole shell area).

Here's an example:

.. sourcecode:: java

   public class MyApp extends Application implements GravitonRunInShell {
      @Override
      public Scene createScene(Graviton graviton) {
          Button root = new Button("Hello world!");
          if (graviton != null)
              return new Scene(root, graviton.getWidth(), graviton.getHeight());   // Fill the browser area.
          else
              return new Scene(root);
      }

      @Override
      public void start(Stage primaryStage) {
          primaryStage.setTitle("MyApp");
          if (primaryStage.isShowing()) return;   // Running in Graviton so bail out.

          // Running outside of Graviton, set up the stage.
          primaryStage.setScene(createScene(null));
          primaryStage.show();
      }
   }
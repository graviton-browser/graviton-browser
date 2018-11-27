Graviton apps
*************

Graviton can run any JVM app with a main method or subclass of ``javafx.application.Application``. However, apps can
opt-in to new features that Graviton makes available to you.

There are two ways to opt in to features: by adding an attribute to your app's MANIFEST.MF file, or by implementing
the Graviton API.

Using the API
-------------

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

With Maven you don't need to change your POM, just change the command line::

    mvn deploy -DaltDeploymentRepository=dev-local::default::file://$HOME/.m2/dev-local

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

Reusing the shell window
------------------------

You can reuse the Graviton window to avoid annoying flicker and disappearing/reappearing shell windows. This may also
make startup faster because you will also be running in the Graviton JVM. This imposes some requirements on you, but
they are not onerous.

The primary requirement is that you test your app thoroughly, and in particular, you test your app after starting it
from the same shell twice. At this time Graviton does not provide total isolation between app runs - if you reconfigure
the JVM by e.g. registering classes with various APIs, those registered classes may still be there when you restart.
You should also avoid making big changes to the window, and accept it how you findit.

Reusing the shell window: Manifest
-------------------------------------

Add ``Graviton-Features: inline`` alongside the ``Main-Class`` entry in your JAR manifest. With Maven you can do it like this::

    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <configuration>
            <archive>
                <manifestEntries>
                    <Graviton-Features>inline</Graviton-Features>
                    <Main-Class>com.example.Main</Main-Class>
                </manifestEntries>
            </archive>
        </configuration>
    </plugin>

Or with Gradle, like this::

    jar {
        manifest {
            attributes(
                'Graviton-Features': 'inline',
                'Main-Class', 'com.example.Main'
            )
        }
    }

The JavaFX ``start`` method of your app will be called as normal with a hidden stage, so you can change the top
level scene of the stage then show it.

Reusing the top level window: API
---------------------------------

If you use the Graviton API to reuse the window, you get access to a ``Graviton`` interface that lets you interface
with the browser. This feature is not available if you go the manifest route. To do this:

1. Implement a JavaFX app by subclassing ``javafx.application.Application`` as normal. Set this to be your
   main class in your application manifest as above.
2. Add a dependency on the ``app.graviton:graviton-api` library in your build file.
3. Implement the ``GravitonRunInShell`` interface on your main class. It requires one method ``createScene``, which
   takes a ``Graviton`` object and returns a JavaFX ``Scene``.
4. Refactor your ``start(Stage)`` method so the part that configures your ``Scene`` is moved into the ``createScene``
   method. From ``start`` just pass null to the parameter.
5. Adjust your ``start`` method so if the stage is already visible, you don't attempt to set the scene or modify the
   window in other ways beyond adjusting the title.
6. Adjust your ``createScene`` method so if the Graviton parameter is non-null, you pass the width and height obtainable
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
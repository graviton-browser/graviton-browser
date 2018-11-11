Graviton apps
*************

Graviton can run any JVM app with a main method or subclass of ``javafx.application.Application``. However, apps can
opt-in to new features only available when running inside Graviton by making some simple changes.

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

Version targeting
-----------------

Before you can use any Graviton features you must declare a *target version*. This is a simple number that specifies which
version of Graviton you have tested your app against. It's the same concept as used in Android and iOS. The intention
is that future versions of Graviton can look at your app and avoid potentially breaking behavioural changes, for instance
by loading backwards compatibility code. By increasing your target version number you opt out of these backwards
compatibility modes and potentially benefit from new capabilities.

Target versions are declared in the MANFIEST.MF file. The only currently supported target version is 1. Do it like this:

.. sourcecode:: groovy

   jar {
       manifest {
           attributes("Graviton-Target": 1)
       }
   }

Reusing the top level frame
---------------------------

Currently there is only one special feature available: you can opt in to re-using the top level window that the GUI
creates for you. This avoids a jarring transition as Graviton hides its shell window and starts your own app.
Instead the contents of the shell are smoothly replaced by your own app's content.

TODO
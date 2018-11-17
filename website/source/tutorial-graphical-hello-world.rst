Tutorial: graphical hello world
*******************************

Graviton can run unmodified Java apps, but you can improve the user experience by making small upgrades to your program.

This tutorial takes you through how to make a simple GUI app and then improve how it runs. This isn't the only way
to publish software for Graviton - it's just a gentle getting started guide.

.. note:: You can find the code for this app with one commit per stage `here, on GitHub <https://github.com/graviton-browser/hello-world>`_.

Step 1: publish from GitHub
===========================

Make a hello world app that uses JavaFX. It could also use Swing, SWT, OpenGL etc but for now pick JavaFX. Here's
what such an app might look like:

.. sourcecode:: java

   package hello

   import javafx.application.Application;
   import javafx.scene.Scene;
   import javafx.scene.control.Button;
   import javafx.scene.layout.StackPane;
   import javafx.stage.Stage;

   public class HelloWorld extends Application  {
       @Override
       public void start(Stage primaryStage) throws Exception {
           StackPane helloPane = new StackPane(new Button("Hello world!"));
           primaryStage.setScene(new Scene(helloPane));
           primaryStage.setWidth(300);
           primaryStage.setHeight(200);
           primaryStage.show();
       }
   }

Of course it could also use Kotlin, Scala, Haskell, Ruby etc as these languages all run on the JVM.

Use Gradle or Maven as the build system. Your IDE can generate such build files if you aren't sure where to start.
A simple Gradle ``build.gradle`` file for the above program might look like this:

.. sourcecode:: groovy

   plugins {
       id 'java'
   }

   group 'com.github.yourusername'
   version '1'

The group should be set to a package / reverse DNS name you control. If you don't have one, you can use the GitHub
pattern shown above.

Talking of which, now upload your hello world app to GitHub and use the GitHub UI to make a release of version 1.

.. note:: The description you provide for your GitHub repository will appear in the recent apps list, so set a good one!

Open Graviton and enter ``com.github.yourusername:reponame`` where obviously the string is derived from the URL
of your repository (``https://github.com/yourusername/reponame`` for the prior example). Graviton will work in
conjunction with `JitPack <https://jitpack.io>`_ to check out your source code from your repository, build it,
package it into JARs, publish it via a Maven repository and then download it to your computer and run it.

Try clearing the cache and starting the app again. It'll download much faster this time as JitPack won't need to download
your code or build it.

Now try starting your app once last time from the recent apps list. It should start instantly. That's how it'll be
for your users after their first run too, as updates are applied asynchronously in Graviton. Your app will also work
offline.

Now change the label on the button and make a new GitHub release. Either go do something else for a few hours, or
just right click on the app tile and select 'Refresh'. When you start your app from Graviton again, it'll be on the
latest version. Your users should get the new version within about 6 hours or so if their computer is switched on,
or shortly after they've logged in when it's been off for a while.

That's it! You just published your first desktop app, direct from your git repository.

.. note:: JitPack is useful for development purposes but you will probably want to do your own builds in future.
   For that you should investigate BinTray, which lets you easily create and publish programs to the JCenter Maven
   repository. In future this tutorial may cover how to do that.


Step 2: Specify the main class name
===================================

In the first step, we made a class that subclasses ``javafx.application.Application``. That was sufficient for Graviton
to figure out where the program should begin. But this way to start apps will get slower as our app gets bigger, because
Graviton had to scan all the files in our app looking for ``Application`` subclasses.

It's more efficient to tell Graviton exactly where the program is meant to start. Add the ``application`` plugin in
your Gradle file, set the ``mainClassName`` property and then set the version to 2, so it looks like this:

.. sourcecode:: groovy

   plugins {
       id 'java'
       id 'application'
   }

   group 'com.github.yourusername'
   version '2'

   mainClassName = 'hello.HelloWorld'

Commit it, push to GitHub and make a new release. You probably won't see any observable difference in speed just yet,
but it's good practice to always explicitly specify your entry point.


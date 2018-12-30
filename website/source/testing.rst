Testing
*******

Here are some initial notes on testing Graviton. In the early days, testing is done through a mix of unit tests
and manually executed test plans. As the product definition and UI stabilise, this will shift towards heavy usage
of integration tests.

Using WireMock to simulate Maven repositories
=============================================

`WireMock <http://wiremock.org/>`_ is a Java program that can simulate web servers, with many features. Especially
useful for us is the ability to record and replay interactions with real servers, customised in various ways and
with fault/slowdown injection. The interactions are stored to JSON files.

WireMock can be used via Graviton itself. To make a recording of the interaction with a Maven repository follow
these steps.

Firstly, run ``graviton com.github.tomakehurst:wiremock-standalone`` in a new empty directory.

Now run::

    curl -d '{"targetBaseUrl": "http://repo1.maven.org/maven2", "extractBodyCriteria": {"binarySizeThreshold": "1kb"}}' http://localhost:8080/__admin/recordings/start

This will set up a recording proxy that will interpose on Maven Central (obviously change the URL as desired but watch out, don't add a trailing slash).

Invoke Graviton like this::

    graviton --cache-path=/tmp/gravicache --repositories=http://localhost:8080 com.github.ricksbrown:cowsay moo

to download via the proxy into a fresh cache directory.

Finally, use ``curl -d '{}' http://localhost:8080/__admin/recordings/stop`` to stop the recording.

The directory you ran WireMock in will now have two directories, files and mappings. Any binary larger than 1kb will
have been put in the files directory, textual responses or small binary responses will be in JSON files in mappings.
If you re-run the same Graviton command against the WireMock server, all the answers will come from the recording.
You can test this by disabling your wifi or ethernet connection.

Test plans
==========

This document contains some manual test plans. At some point we should automate them, although
testing software like Graviton can be tricky. We'll need to use an HTTP server that can
replay recorded interactions with real Maven repositories, and use lots of TestFX for the GUI,
mock filesystems and so on. Then integration tests for the OS integrations/background updates.

Pre-flight checklist
--------------------

Build the package for each OS in turn. Ensure there are no Graviton cache directories anywhere and remove them if so.
Install the package.

Mac installer
~~~~~~~~~~~~~

- Make sure your Mac security settings are configured to be the default, i.e. Gatekeeper is active.
- Ensure there are no Graviton installs or data directories already.
- Open the mac DMG and drag it to the Applications folder. Ensure the icon and DMG background look right.
- Start the app from the Applications folder and ensure it starts, that the menu bar shows the name Graviton.

Windows installer
~~~~~~~~~~~~~~~~~

- Click the Windows installer. Ensure it installs automatically and with no user interaction.
- Find the app in the start menu and start it. Shut it down.
- Open task scheduler and check the background update task is registered. Run it manually and check that in the log
  that the update process ran up to the point that it knows it's up to date.
- Go into the control panel and uninstall Graviton. Ensure the uninstall completes without errors
  (this is a common failure point!)

CLI
~~~

- Run ``graviton --help`` and ensure a useful help output is created. On Windows, ensure it's in colour.
- Run ``graviton --version`` and ensure the version number is correct.
- Run ``graviton com.github.ricksbrown:cowsay --cowthink moo!`` and ensure it downloads and runs correctly.
- Run it again and ensure there's no download or other UI text this time, just the cow.
- Go offline (disable wifi) and run ``graviton --clear-cache com.github.ricksbrown:cowsay "cannot work"``. Check
  the error message you get is sensible and helpful.
- Run ``graviton --clear-cache com.github.ricksbrown:cowsay "Downloaded again!"`` and ensure it re-downloads and runs like before.
- Run ``graviton -r com.github.ricksbrown:cowsay moo`` and ensure it checks for an update again.
- Edit the history file to set the time of the last update to more than 24 hours ago. Then run
  ``graviton --offline com.github.ricksbrown:cowsay moo`` and ensure it doesn't check for an update even though
  the history entry is old.

GUI shell
~~~~~~~~~

- Start the GUI. Run Tic-Tac-Toe and ensure it downloads properly, the main window disappears, TTT starts. Quit and ensure GUI is restored.
- Ensure the history list populates with an app tile for it.
- Start ``com.github.rohitawate:everest`` and click cancel during the download. Make sure it stops.
- Start Everest again, this time, quit during the download and ensure Graviton properly quits.
- Start it for a third time and this time let it succeed.
- Right click on one of the tiles and ensure each item works.

Online update
~~~~~~~~~~~~~

- Clear your cache. Start ``net.plan99:tictactoe:1.0.1`` - it should download an old version of the app. Now go into
  your history file and edit it so the version number is missing from the coordinate fragment field.
  This makes it look like the user just hasn't updated for a while and a new version has been released.
- Start Graviton GUI. Now from the command line run ``graviton --background-update`` and check the log file to ensure
  it updated you to the latest version of tictactoe. Start the app in the GUI ensure it's now the latest version.

**If the bootstrapper, installers, packaging or runtime update code was changed, for each platform**:

- Bump the version number and prepare an update site that has the dummy update
- Install the current (under test) version
- Leave the machine or VM until an update check interval has passed, with Graviton running
- Make sure that the update has been downloaded, applied and restarting Graviton makes it appear

Next steps to improve testing
=============================

1. Implement or find a recording HTTP proxy that can simulate errors and slowness. WireMock isn't quite there for us,
   I could never make its browser proxy mode really work. Make recordings of installing various apps. The manual
   test cases should now be executable entirely offline.
2. Write an integration test tool that verifies, given a random/clean cache directory:
   * the CLI tool can be used to install and run basic programs
   * when run against the proxy recordings
3. Write a basic TestFX test that generates a clean cache directory and installs/runs TicTacToe from the
   proxy recordings.
4. Extend the test to ensure that the clear cache function works.
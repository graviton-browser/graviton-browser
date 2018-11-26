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
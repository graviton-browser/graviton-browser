Introduction
************

We would like a competitor to the web browser for JVM application distribution and deployment. This would be an alternative
to applets, the now deprecated Java Web Start and javapackager style bundled distribution.

Initial target markets:

* Internal apps in industrial and enterprise scenarios, e.g. finance and trading applications where web apps are often
  not preferred for productivity/usability/security and other reasons. Slick updates and a deploy-once runtime are
  useful here.
* Hobbyist and learner apps where users do not want the overhead of the complex web frontend stack or desktop deployment,
  but do want to publish their apps. They aren't attempting to maximise the user acquisition funnel so don't mind
  telling users to download an app browser component. These are people who distribute JARs or only source code today.
* The Java gaming community.
* People who want to write cross platform command line apps.
* IoT devices that struggle to provide secure administration interfaces, given the demands of web browser makers to use
  web PKI SSL and the poor fit of SSL with embedded web servers that do not have domain names.
* People who want to write apps in languages other than JavaScript, although we plan for JS to be a first class citizen
  along with all other mainstream languages (via the Truffle project).

We feel these market segments are under-served by web browser developers.

The resulting platform should use the JVM for platform independence, sandboxing, code streaming and other services but
should otherwise be language and UI toolkit agnostic to the extent possible.

FAQ
===

**Why the JVM?** We don't want to repeat the web's mistake of restricting us to a single language. The JVM has the best
support for running multiple languages and having them interoperate. It also has some of the best cross platform user
interface frameworks, many excellent libraries and modules, good tools and a relatively clean design for its age. Through Graal
and Truffle languages we can combine not only scripting languages like JavaScript, Python and Ruby but also C/C++
modules via Sulong. This gives us a direct equivalent to WebAssembly.

**Aren't JVMs bloated and sluggish?** Historically yes. We aren't worried about this though for three reasons.
(1) Our competition isn't expertly written C++ apps but web apps, which are much worse. (2) The bulk of the JVM's
reputation for sluggishness comes from startup time and memory usage, not peak runtime performance. All of these
are being tackled by the JVM team through recently added features like ahead of time compilation, AppCDS (class data
pre-computation and sharing), GCs that don't pause the application and other such features. (3) The JVM's reputation
largely dates from the late 1990's and early 2000's when hardware wasn't as good as it is now. Over time hardware got
bigger and JVMs got more efficient. So we aren't worried about this so much anymore.

**Do apps need to be written specifically for it?** No, Graviton can download and run ordinary Java apps with a main
method that have been uploaded to a Maven repository or github. There are many such apps already. But with small
adaptations, the user experience will get a lot better. As such there is no bright line between a JVM app and a
Graviton app. Learn more about :doc:`graviton-apps`.
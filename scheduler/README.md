# Graviton Scheduler

Graviton Scheduler is an abstraction over the task scheduling component of the underlying operating system, with the
primary use case being daily invocation of a background online update check for Graviton Browser. It is written in Kotlin
but can be used by any JVM language.

# Usage

Obtain an OSTaskScheduler instance like this:

`val scheduler = OSTaskScheduler.get()`

Note that this method may return null if the host OS is not recognised (i.e. is Linux).

Then create an `OSScheduledTaskDefinition` and fill out its properties. Finally, pick a name in dotted reverse DNS
form for your task, for example "net.plan99.example.task". Call `scheduler.register` with the task name and definition
object to register it, and `scheduler.deregister` with the task name again to remove it.

# macOS

There is currently support for macOS via Launch Services, with the task being installed as the current user 
(under ~/Library/LaunchAgents).

To register a task to run daily, at a time of the platform's choosing:

```
val task = OSScheduledTaskDefinition(
                    Paths.get("/Applications/Foo Bar.app/Contents/MacOS/Foo Bar"),
                    listOf("--a", "--b"),
                    frequency = Duration.ofHours(12)
           )

MacTaskScheduler().register("com.foo.bar.mytask", task)
    
// later on

MacTaskScheduler().deregister("com.foo.bar.mytask")
``` 

At this time there is only support for recurrences and not requesting to be scheduled at specific times of day.
That feature would be easy to add if required.

# Windows

There is support for the Windows Task Scheduler, with some caveats.

1. The task is installed and runs as the current user (when they're logged in).
2. The frequency can only be an integer number of days.
3. The task always runs at 8pm each day. This would be easy to make configurable.

Windows supports two fields in OSScheduledTaskDefinition that macOS ignores: `description` and `networkSensitive`.
The description appears in the Task Scheduler GUI. This is a good place to plead with your user to not disable
or delete your task. The `networkSensitive` field should be set to true if your background task needs network
access. If the computer is disconnected then the task won't run until the network is back.

```
WindowsTaskScheduler().register("net.plan99.graviton.browser.update", OSScheduledTaskDefinition(
        Paths.get("c:\\Users\\mike\\AppData\\Local\\GravitonBrowser\\GravitonBrowser.exe"),
        listOf("--a", "--b"),
        networkSensitive = true,
        description = "Graviton Browser online update tasks. Do not disable this, if you do the app may become out of date and insecure."
))
```

Note that the dotted reverse DNS task name will be transformed into an English-style name by removing the dots, removing
the following prefixes (net, org, com, co, io) and then capitalising each word. So "net.plan99.graviton.scheduler.example.task"
will appear in the Task Scheduler GUI as "Plan99 Graviton Scheduler Example Task".
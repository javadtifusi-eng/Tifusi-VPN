# The Xray core (libv2ray.aar) is a gomobile binding: the Go runtime resolves these classes and
# their methods from native code, by name, so R8 can neither drop nor rename any of them. Same for
# the app's CoreCallbackHandler implementation, which Go calls back into.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-keep class * implements libv2ray.CoreCallbackHandler { *; }

# Names are kept as written. Shrinking is what saves the megabytes; renaming on top of it saves
# little and would turn every crash report from a user into unreadable stack traces.
-dontobfuscate

# Crash reports from a user's phone are the only debugging channel this app has.
-keepattributes SourceFile,LineNumberTable

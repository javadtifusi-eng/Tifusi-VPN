# Shrink and optimise, but keep class and member names: failure reasons sent to the panel
# (ConnectionReports) and crash traces name exception classes, and they must stay readable.
-dontobfuscate

# The Xray core (libv2ray.aar) calls back into Java over JNI by name; the AAR ships its own
# keep rules for go.** and libv2ray.**, these cover the app's implementations of its interfaces.
-keep class * implements libv2ray.** { *; }

# Shrink and optimise, but keep class and member names: failure reasons sent to the panel
# (ConnectionReports) and crash traces name exception classes, and they must stay readable.
-dontobfuscate

# strongSwan's libandroidbridge looks up these classes, their methods and constructors over JNI
# by name (org.strongswan.android.logic.CharonVpnService and its helpers).
-keep class org.strongswan.android.** { *; }

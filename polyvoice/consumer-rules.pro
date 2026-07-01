# Copyright PolyAI Limited
#
# Consumer R8 keep rules shipped inside the AAR and applied automatically by the integrating
# app's R8 pass. The SDK's own Kotlin uses no reflection; the only reflective/JNI surface is
# libwebrtc, which calls back into its Java classes from native code by name.
#
# The webrtc-sdk AAR ships NO consumer proguard rules of its own (verified: the artifact contains
# only the manifest, classes.jar, and the native .so libs). org.webrtc is reached purely via JNI
# from native code by name, which R8 cannot see — so these keep/-dontwarn rules are REQUIRED, not a
# redundant safety net: removing them would let any consumer with minifyEnabled strip JNI-reached
# members and crash the native engine at call time. (A package-wide `{ *; }` keep is heavier than
# ideal, but appropriate for an opaque JNI surface.)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

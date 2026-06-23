# Copyright PolyAI Limited
#
# Consumer R8 keep rules shipped inside the AAR and applied automatically by the
# integrating app's R8 pass. Keep this MINIMAL — the wire layer uses org.json with
# manual (non-reflective) mapping, so almost nothing needs keeping.
#
# The public API reached from the app's own call sites is kept by R8 automatically;
# only types reached purely by reflection/JNI/name-lookup need explicit rules.
#
# (Intentionally near-empty for zero-config integration.
#  Add narrow member-level -keepclassmembers rules here only if a concrete
#  reflection/serialization break is found — never package-wide `-keep ... { *; }`,
#  and never global flags like -dontoptimize/-dontobfuscate in consumer rules.)

# androidx.security-crypto (the SDK's session-store dependency) pulls Google Tink, whose
# bytecode references ErrorProne's compile-only annotations. They are absent at runtime by
# design, but R8 treats the dangling references as "missing classes" and FAILS any consumer
# build with minifyEnabled — verified in a fresh consumer project. -dontwarn for annotation
# classes is the canonical, safe suppression (annotations are never loaded at runtime).
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

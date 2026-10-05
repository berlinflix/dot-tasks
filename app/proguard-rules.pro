# --- Privacy: strip all android.util.Log calls from release builds (no task text can leak to logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}

# --- SQLCipher: JNI looks up these classes by name.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# --- kotlinx.serialization (navigation routes, sync payloads)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Consumer ProGuard rules — applied automatically to apps that depend on this library.

# Keep the public AdSparkle API so it survives minification in consumer apps.
-keep public class co.adsparkle.sdk.AdSparkle {
    public static *;
}
-keep public class co.adsparkle.sdk.ConversionResult { *; }
-keep public class co.adsparkle.sdk.ConversionResult$* { *; }
-keep public enum  co.adsparkle.sdk.EventType { *; }

# org.json is used internally — keep it from being stripped.
-keep class org.json.** { *; }

# Kotlinx coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

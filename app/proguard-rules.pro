-keep class go.** { *; }
-keep class com.sky22333.netboot.core.** { *; }
-keep class com.sky22333.netboot.root.RootBrokerMain {
    public static void main(java.lang.String[]);
}
-dontwarn org.jetbrains.annotations.**
-keepclassmembers class * implements com.sky22333.netboot.data.MediaProgress {
    public boolean onProgress(int, long, long);
}

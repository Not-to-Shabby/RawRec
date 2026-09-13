# RawRec R8 / ProGuard Configuration

# Line numbers and source attributes for production crash logging (CrashHandler)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Native JNI methods and bindings (librawrec.so)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Native Codec interfaces and entrypoints
-keep class dev.rawrec.app.codec.RawPackNative { *; }
-keep class dev.rawrec.app.codec.ZstdNative { *; }
-keep class dev.rawrec.app.codec.** { *; }

# Foreground capture service & components referenced in AndroidManifest
-keep class dev.rawrec.app.capture.CaptureForegroundService { *; }
-keep class dev.rawrec.app.MainActivity { *; }

# Dynamic camera vendor tag inspection & reflection
-keep class dev.rawrec.app.capture.VendorTags { *; }

# Crash handling and telemetry
-keep class dev.rawrec.app.util.CrashHandler { *; }

# Desktop CLI / GUI and GPU reflection targets
-keep class dev.rawrec.tool.RvtoolGui { *; }
-keep class dev.rawrec.tool.gpu.** { *; }
-keep class dev.rawrec.tool.** { *; }

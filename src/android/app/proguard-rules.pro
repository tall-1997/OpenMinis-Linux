# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# gomobile rclone binding — JNI / generated Java must keep original names
-keep class com.openminis.rclone.** { *; }
-dontwarn com.openminis.rclone.**

# Rhino (org.mozilla.javascript) references desktop java.beans / javax.lang.model.
# Android R8 treats those as missing and fails minifyRelease (1.33 CI).
-dontwarn java.beans.**
-dontwarn javax.lang.model.**
# Rhino JavaMembers clinit loads javax.lang.model.SourceVersion by name.
# The Android stub lives in src/main/java/javax/lang/model/SourceVersion.java.
# R8 otherwise treats the package as unused and strips it, recreating
# NoClassDefFoundError on the first execute_code / javaToJS.
-keep class javax.lang.model.** { *; }
# :core:model packages org.json:json:20231013 into the APK. R8 renaming those
# classes makes ART's org.json.JSONStringer (bootclasspath) disagree with the
# app's renamed copy and crashes ACRA. Keep the names; do not shrink them.
-keep class org.json.** { *; }
-dontwarn org.json.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
#
# [T-android-rhino-vmbridge-keep] execute_code uses Context.enter() which
# Class.forName()s VMBridge implementations
# (org.mozilla.javascript.jdk18.VMBridge_jdk18, …). R8 sees no Java call
# graph to those types, strips them, and release builds crash on the first
# execute_code with ExceptionInInitializerError / "Failed to create
# VMBridge instance". Keep the whole engine — the sandbox is small next to
# the rest of the APK, and a narrow keep that missed a reflective lookup
# would fail the same way as VAD JNI did in 1.12.
#
# NOTE FOR VERIFICATION: debug builds don't minify. Check against
# assembleRelease (and that mapping.txt still lists org.mozilla.javascript).
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }

# [T-ssh-backend] JSch instantiates kex/cipher/mac/signature/compression
# implementations from its default config table via Class.forName — R8 sees
# no call graph edge to com.jcraft.jsch.jce.* / jgss.* and strips them, so
# every release-build connection dies with ClassNotFoundException while
# debug (no minify) works. Same failure class as the Rhino VMBridge keep
# above. Optional integrations (BouncyCastle, jzlib) are absent by design.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jzlib.**

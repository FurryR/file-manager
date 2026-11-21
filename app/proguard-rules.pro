# R8 keep rules for the release build.
#
# The release variant is debuggable on purpose (only a debuggable APK extracts
# lib/<abi>/{run0,here,agent} into nativeLibraryDir). Debuggable already turns
# code optimization and obfuscation off; -dontobfuscate makes that explicit so
# turning debuggable off later cannot silently rename reflected members.
-dontobfuscate

# Called through Class.forName("rikka.shizuku.Shizuku") in DaemonManager.
-keep class rikka.shizuku.** { *; }

# JSch resolves cipher/kex/signature implementations from string names.
-keep class com.jcraft.jsch.** { *; }

# commons-net instantiates FTP config/parser classes from string names.
-keep class org.apache.commons.net.** { *; }

# R8 8.6 miscompiles joni's enum-unboxed REDUCE_TABLE. All static fields are
# removed and QuantifierNode.<clinit> becomes `throw null`, so the first regex
# built by the TextMate tokenizer kills the editor thread with
# ExceptionInInitializerError; reduceNestedQuantifier is corrupted the same
# way. Keep the engine's enums so R8 cannot unbox them.
-keep enum org.joni.** { *; }
-keep enum org.jcodings.** { *; }

# protobuf-javalite reaches generated messages through runtime reflection.
-keep class io.furryr.file.proto.** { *; }

# Termux classes are rewritten by the Gradle ASM instrumentation before R8 and
# read reflectively by TerminalSelectionBridge (fields/methods by name).
-keep class com.termux.** { *; }

# TextMate grammars/themes are read from assets; tm4e clones configuration
# objects through Class.getMethod("clone"), so no-arg clone methods have to
# survive shrinking even though no direct call site exists.
-keep class org.eclipse.tm4e.core.internal.utils.ObjectCloner { *; }
-keepclassmembers class org.eclipse.tm4e.** {
    public *** clone();
}

# tm4e deserializes language-configuration JSON through Gson. The model has no
# direct instantiation site, so R8 optimization makes LanguageConfiguration
# abstract and drops its no-arg constructor; Gson then fails with "Abstract
# classes can't be instantiated". Keep the whole model package (a handful of
# tiny classes) as a reflective entry point.
-keep class org.eclipse.tm4e.languageconfiguration.internal.model.** { *; }

# Same Gson pattern in sora's grammar list reader: the model has no direct
# instantiation site and only a non-default constructor, so Gson currently
# falls back to Unsafe allocation. Keep it from being abstractified too.
-keep class io.github.rosemoe.sora.langs.textmate.registry.reader.LanguageDefinitionReader$LanguageDefinitionList { *; }

# JNI entry points and native->Kotlin callbacks addressed by name.
-keep class io.furryr.file.wayland.NativeCompositor { *; }
-keep class io.furryr.file.pulse.PulseNative { *; }
-keep class io.furryr.file.adblock.AdBlockNative { *; }
-keepclassmembers class io.furryr.file.pulse.PulseService {
    public void onSinkData(int);
    public void onSinkIdle();
    public void onMicState(boolean);
}
-keepclasseswithmembernames class * { native <methods>; }

# Optional backends referenced by kept libraries but never shipped: JSch's
# BouncyCastle/JNA/GSS/slf4j paths, jcodings' log4j bridges, the annotation-only
# tm4e reference and Android's removed sun.security.x509 shim.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
-dontwarn sun.security.x509.**

# Manifest components are kept by the AAPT-generated rules; these two are also
# resolved through content:// authorities and exported URIs.
-keep class io.furryr.file.forward.ForwardFileProvider { *; }
-keep class io.furryr.file.picker.PickerDocumentsProvider { *; }

# kotlinx.serialization keeps its generated serializers on the companion.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses class com.tannmenghong.tbchat.**$$serializer { *; }
-keepclassmembers class com.tannmenghong.tbchat.** {
    *** Companion;
}

# JNI entry points are called by name from C++ and are invisible to R8.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.tannmenghong.tbchat.inference.llamacpp.LlamaNative { *; }
-keep interface com.tannmenghong.tbchat.inference.llamacpp.LlamaNative$* { *; }

# AIDL stubs cross a process boundary and are resolved reflectively.
-keep class com.tannmenghong.tbchat.inference.service.IInferenceService** { *; }
-keep class com.tannmenghong.tbchat.inference.service.IInferenceCallback** { *; }

-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*

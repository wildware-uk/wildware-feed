# Ktor and OkHttp mention classes that only exist on the JVM or in optional modules.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin serialization keeps what it needs through its own rules; the saved session is one of ours.
-keep,includedescriptorclasses class dev.wildware.agents.**$$serializer { *; }
-keepclassmembers class dev.wildware.agents.** {
    *** Companion;
}

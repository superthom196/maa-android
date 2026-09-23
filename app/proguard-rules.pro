# kotlinx.serialization: keep generated serializers for our models.
-keepclassmembers class io.github.superthom196.maa.** { *** Companion; }
-keepclasseswithmembers class io.github.superthom196.maa.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.superthom196.maa.**$$serializer { *; }
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# kotlinx.serialization: keep generated serializers for our models.
-keepclassmembers class io.github.superthom196.maa.data.** { *** Companion; }
-keepclasseswithmembers class io.github.superthom196.maa.data.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.superthom196.maa.data.**$$serializer { *; }
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

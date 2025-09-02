# Add project specific ProGuard rules here.
# You can edit the include path and order by changing the ProGuard
# include property in project.properties.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

-keep class com.statsig.** { *; }

# Keep Kotlin metadata for reflection
-keepclassmembers class kotlin.Metadata { *; }

# Keep Kotlin lambdas and companion objects
-keepclassmembers class * {
    *** Companion;
}

-keep class com.statsig.**$$Lambda$* { *; }
-keep class **$$ExternalSyntheticLambda* { *; }

# Keep all anonymous classes (used by computeIfAbsent lambda)
-keepclassmembers class com.statsig.**$* { *; }
-keepclassmembers class com.statsig.**$$* { *; }

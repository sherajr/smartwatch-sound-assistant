# Release minification is off (personal sideloaded build). If it is ever turned on, keep the
# kotlinx.serialization-generated serializers for the shared wire contracts and Play services.
-keepclassmembers class com.peaceantz.stagescope.shared.** { *** Companion; }
-keepclasseswithmembers class com.peaceantz.stagescope.shared.** { kotlinx.serialization.KSerializer serializer(...); }

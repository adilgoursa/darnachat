# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.database.PropertyName <fields>;
}

# Firebase Realtime Database deserializes our model classes via reflection
# (DataSnapshot.getValue(Message::class.java) / User::class.java), which
# needs the no-arg constructor and field names to survive R8 shrinking.
-keep class com.adil.chatapp.model.** { *; }
-keepclassmembers class com.adil.chatapp.model.** {
    <init>();
}

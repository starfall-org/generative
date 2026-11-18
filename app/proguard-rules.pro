# Google Code Scanner 16.1.0 discovers ML Kit components through reflection.
# Its current AAR lacks the keep rules required by R8 full mode (AGP 9+).
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar

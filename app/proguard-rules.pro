# Add project specific ProGuard rules here.
-keepclassmembers class com.deskpet.overflow.service.OverlayService {
    public *;
}
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn org.json.**
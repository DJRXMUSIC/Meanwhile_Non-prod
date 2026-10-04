# R8 is currently disabled for release (see docs/DECISIONS.md). Rules kept here for when it is enabled.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

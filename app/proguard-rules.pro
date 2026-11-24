# Lottie and Compose ship their own consumer rules. okio (pulled in by Lottie) mentions an annotation
# class that is not on the classpath; it is only a nullability marker.
-dontwarn javax.annotation.**

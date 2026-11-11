# Deixis: R8 ужимает и оптимизирует Compose, AndroidX и стандартную библиотеку Kotlin — интерфейс быстрее, APK меньше.
# Осторожно: всё, что работает через отражение, JNI или загрузку по имени, сохраняется целиком.

# Имена не перемешиваются: журнал ошибок и «Диагностика» остаются читаемыми.
-dontobfuscate

# Свой код — целиком (протокол на kotlinx.serialization, AirPlay, приёмник).
-keep class app.deixis.** { *; }

# Сеть: сервер и клиент Ktor (движки и плагины находятся по имени), mDNS, шифрование.
-keep class io.ktor.** { *; }
-keep class javax.jmdns.** { *; }
-keep class org.bouncycastle.** { *; }

# Проигрыватель VLC: классы вызываются из нативного кода.
-keep class org.videolan.** { *; }

# QR-коды.
-keep class com.google.zxing.** { *; }

# kotlinx.serialization: сериализаторы ищутся через Companion.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

# Классы JVM, которых нет на Android и которые библиотеки трогают только на компьютере.
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn org.bouncycastle.**
-dontwarn javax.jmdns.**
-dontwarn reactor.blockhound.**
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.ProcessHandle$Info

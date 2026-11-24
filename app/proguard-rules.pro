# Apache SSHD (the file server) finds ciphers, factories and its I/O service by name.
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
-dontwarn javax.management.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.apache.tomcat.jni.**

# ML Kit's code scanner (the pairing QR) finds its components through manifest metadata and
# instantiates each registrar by name. R8 full mode drops a constructor nothing calls
# directly, and without the registrars the scanner's task executor is never registered
# (NullPointerException in GmsBarcodeScanning.getClient on tap).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.common.internal.MlKitComponentDiscoveryService

# Shizuku starts the stats user service by class name in a shell-uid process.
-keep class dev.ii.tools.shell.ShellStatsService { <init>(...); }

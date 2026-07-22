# sshj resolves transports, key algorithms and ciphers reflectively via its
# Factory service lists, so the implementation classes must survive shrinking.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.naming.**

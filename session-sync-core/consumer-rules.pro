# JSch loads crypto, compression and key exchange implementations by their
# original class names from its runtime config. R8 cannot see those reflective
# references, so keep the package intact for SFTP/SSH.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

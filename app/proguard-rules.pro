# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK proguard defaults.

# Keep Shizuku UserService entry point
-keep class net.harveywilliams.bluetoothbouncer.shizuku.BluetoothBouncerUserService { *; }

# Keep AIDL generated classes
-keep class net.harveywilliams.bluetoothbouncer.IBluetoothBouncerUserService { *; }
-keep class net.harveywilliams.bluetoothbouncer.IBluetoothBouncerUserService$* { *; }

# Keep Room entities
-keep class net.harveywilliams.bluetoothbouncer.data.** { *; }

# Diagnostics: ShizukuHelper reads the system log via the private Shizuku.newProcess
-keepclassmembers class rikka.shizuku.Shizuku { private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String); }

# Shell helper: started by name via app_process (see ShellChannel), never referenced directly
-keep class net.harveywilliams.bluetoothbouncer.shizuku.ShellMain { public static void main(java.lang.String[]); }
-keep class net.harveywilliams.bluetoothbouncer.shizuku.BluetoothPolicyBackend { *; }

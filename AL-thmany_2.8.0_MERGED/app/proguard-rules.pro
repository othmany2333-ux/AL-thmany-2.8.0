# The app uses no reflection-based serializers or networking libraries.
# Keep model enum names because they are persisted in SQLite as stable strings.
-keepclassmembers enum com.althmany.groupmanager.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Shizuku UserService is addressed by class name from UserServiceArgs. Keep it stable in release builds.
-keep class com.althmany.groupmanager.shizuku.ShizukuShellUserService { *; }
-keep class com.althmany.groupmanager.shizuku.IShizukuShellService { *; }
-keep class com.althmany.groupmanager.shizuku.IShizukuShellService$Stub { *; }

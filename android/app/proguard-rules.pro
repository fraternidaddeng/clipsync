# Stage 0 has no release-specific shrinking rules.

# app_process entry points for the bundled privileged host. minify is off
# today; keep them so a later shrink cannot drop the daemon / UserService.
-keep class com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedHostService {
    public static void main(java.lang.String[]);
}
-keep class com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedUserServiceStarter {
    public static void main(java.lang.String[]);
}
-keep class com.clipsync.android.platform.clipboard.shizuku.ClipboardUserService { *; }
-keep class com.clipsync.android.platform.clipboard.shizuku.ClipSyncShizukuProvider { *; }

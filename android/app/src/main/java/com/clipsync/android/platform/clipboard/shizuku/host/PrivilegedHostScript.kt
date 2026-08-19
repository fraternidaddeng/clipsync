package com.clipsync.android.platform.clipboard.shizuku.host

/**
 * Shell script that adb/root already running as uid 0/2000 execs. It does not
 * raise privileges. Placeholders are filled at write time.
 */
internal object PrivilegedHostScript {
    fun render(
        packageName: String = PrivilegedHostConstants.PACKAGE_NAME,
        mainClass: String = PrivilegedHostConstants.HOST_MAIN_CLASS,
        processName: String = PrivilegedHostConstants.HOST_PROCESS_NAME,
    ): String {
        return """
            #!/system/bin/sh
            PACKAGE='$packageName'
            MAIN_CLASS='$mainClass'
            PROCESS_NAME='$processName'
            echo "info: clipsync privileged host begin"
            uid=${'$'}(id -u)
            if [ "${'$'}uid" != "2000" ] && [ "${'$'}uid" != "0" ]; then
              echo "fatal: uid ${'$'}uid is not shell or root"
              exit ${PrivilegedHostConstants.EXIT_FATAL_UID}
            fi
            APK=""
            for arg in "${'$'}@"; do
              case "${'$'}arg" in
                --apk=*) APK="${'$'}{arg#--apk=}" ;;
              esac
            done
            if [ -z "${'$'}APK" ]; then
              line=${'$'}(pm path "${'$'}PACKAGE" 2>/dev/null | tr -d '\r' | head -n 1)
              APK="${'$'}{line#package:}"
            fi
            if [ -z "${'$'}APK" ] || [ ! -f "${'$'}APK" ]; then
              echo "fatal: apk not found"
              exit ${PrivilegedHostConstants.EXIT_FATAL_APK}
            fi
            echo "info: apk ${'$'}APK"
            self=${'$'}${'$'}
            for c in /proc/[0-9]*/cmdline; do
              [ -r "${'$'}c" ] || continue
              pid=${'$'}{c#/proc/}
              pid=${'$'}{pid%/cmdline}
              [ "${'$'}pid" = "${'$'}self" ] && continue
              name=${'$'}(tr '\0' ' ' < "${'$'}c" 2>/dev/null)
              case "${'$'}name" in
                "${'$'}PROCESS_NAME"|*" ${'$'}PROCESS_NAME "*|"${'$'}PROCESS_NAME "*)
                  kill -9 "${'$'}pid" 2>/dev/null
                  echo "info: killed ${'$'}pid"
                  ;;
              esac
            done
            echo "info: starting ${'$'}PROCESS_NAME"
            export CLASSPATH="${'$'}APK"
            if command -v setsid >/dev/null 2>&1; then
              setsid /system/bin/app_process -Djava.class.path="${'$'}APK" /system/bin --nice-name="${'$'}PROCESS_NAME" "${'$'}MAIN_CLASS" </dev/null >/dev/null 2>&1 &
            else
              /system/bin/app_process -Djava.class.path="${'$'}APK" /system/bin --nice-name="${'$'}PROCESS_NAME" "${'$'}MAIN_CLASS" </dev/null >/dev/null 2>&1 &
            fi
            echo "info: spawned"
            exit 0
        """.trimIndent() + "\n"
    }

    fun adbSdcardCommand(packageName: String = PrivilegedHostConstants.PACKAGE_NAME): String =
        "adb shell sh /storage/emulated/0/Android/data/$packageName/${PrivilegedHostConstants.SCRIPT_FILE_NAME}"

    fun userServiceCommand(
        apkPath: String,
        token: String,
        packageName: String,
        className: String,
        processNameSuffix: String,
        callingUid: Int,
    ): String {
        val processName = "$packageName:$processNameSuffix"
        val starter = PrivilegedHostConstants.USER_SERVICE_STARTER_CLASS
        return "(CLASSPATH='$apkPath' /system/bin/app_process " +
            "-Djava.class.path='$apkPath' /system/bin " +
            "--nice-name='$processName' $starter " +
            "--token='$token' --package='$packageName' --class='$className' " +
            "--uid=$callingUid --debug-name='$processName')&"
    }
}

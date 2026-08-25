using System.Runtime.InteropServices;

namespace ClipSync.Peer.Resilience;

/// <summary>
/// Win32 suspend/resume notifications via <c>PowerRegisterSuspendResumeNotification</c>.
/// Delivers Modern Standby (S0 DRIPS) entry/exit as well as classic S3 sleep —
/// unlike <c>Microsoft.Win32.SystemEvents.PowerModeChanged</c>, which is backed by
/// legacy PBT broadcasts that message-loop windows do not receive on S0.
/// Registration is a no-op on non-Windows hosts.
/// </summary>
public sealed class Win32SuspendResumeNotificationSource : ISuspendResumeSource
{
    internal const uint DeviceNotifySubscribeCallback = 2;
    internal const uint PbtApmSuspend = 4;
    internal const uint PbtApmResumeSuspend = 7;
    internal const uint PbtApmResumeAutomatic = 18;

    // The native registration stores an unmanaged function pointer only.
    // Keep this delegate in a field so the GC cannot collect it while
    // PowerRegisterSuspendResumeNotification is active (classic P/Invoke bug).
    private readonly DeviceNotifyCallbackRoutine callback;

    private Action? onSuspend;
    private Action? onResume;
    private nint parametersMemory;
    private nint registrationHandle;
    private int disposed;

    public Win32SuspendResumeNotificationSource()
    {
        callback = OnDeviceNotify;
    }

    public void Subscribe(Action onSuspend, Action onResume)
    {
        ArgumentNullException.ThrowIfNull(onSuspend);
        ArgumentNullException.ThrowIfNull(onResume);
        this.onSuspend = onSuspend;
        this.onResume = onResume;
        Register();
    }

    public void Unsubscribe()
    {
        Unregister();
        onSuspend = null;
        onResume = null;
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
        {
            return;
        }

        Unsubscribe();
    }

    internal static bool TryMapNotification(uint type, out bool isSuspend)
    {
        switch (type)
        {
            case PbtApmSuspend:
                isSuspend = true;
                return true;
            case PbtApmResumeSuspend:
            case PbtApmResumeAutomatic:
                isSuspend = false;
                return true;
            default:
                isSuspend = false;
                return false;
        }
    }

    private void Register()
    {
        if (!OperatingSystem.IsWindows() || registrationHandle != nint.Zero)
        {
            return;
        }

        var parameters = new DeviceNotifySubscribeParameters
        {
            Callback = Marshal.GetFunctionPointerForDelegate(callback),
            Context = nint.Zero
        };
        var memory = Marshal.AllocHGlobal(Marshal.SizeOf<DeviceNotifySubscribeParameters>());
        Marshal.StructureToPtr(parameters, memory, fDeleteOld: false);

        var status = NativeMethods.PowerRegisterSuspendResumeNotification(
            DeviceNotifySubscribeCallback,
            memory,
            out var handle);
        if (status != 0 || handle == nint.Zero)
        {
            Marshal.FreeHGlobal(memory);
            return;
        }

        parametersMemory = memory;
        registrationHandle = handle;
    }

    private void Unregister()
    {
        var handle = Interlocked.Exchange(ref registrationHandle, nint.Zero);
        if (handle != nint.Zero)
        {
            _ = NativeMethods.PowerUnregisterSuspendResumeNotification(handle);
        }

        var memory = Interlocked.Exchange(ref parametersMemory, nint.Zero);
        if (memory != nint.Zero)
        {
            Marshal.FreeHGlobal(memory);
        }
    }

    private uint OnDeviceNotify(nint context, uint type, nint setting)
    {
        if (!TryMapNotification(type, out var isSuspend))
        {
            return 0;
        }

        if (isSuspend)
        {
            onSuspend?.Invoke();
        }
        else
        {
            onResume?.Invoke();
        }

        return 0;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DeviceNotifySubscribeParameters
    {
        public nint Callback;
        public nint Context;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate uint DeviceNotifyCallbackRoutine(nint context, uint type, nint setting);

    private static class NativeMethods
    {
        [DllImport("powrprof.dll", ExactSpelling = true, SetLastError = true)]
        [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
        internal static extern uint PowerRegisterSuspendResumeNotification(
            uint flags,
            nint recipient,
            out nint registrationHandle);

        [DllImport("powrprof.dll", ExactSpelling = true, SetLastError = true)]
        [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
        internal static extern uint PowerUnregisterSuspendResumeNotification(nint registrationHandle);
    }
}

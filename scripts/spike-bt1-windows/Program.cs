using System.Text;
using ClipSync.Peer.Bluetooth;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Radios;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace ClipSync.Spike.Bt1Windows;

/// <summary>
/// PHASE 0 SPIKE (ADR 0005 / docs/bluetooth-phase0-spike.md). Answers, with evidence:
/// can an UNPACKAGED .NET 8 desktop process use WinRT RfcommServiceProvider to publish
/// the frozen ClipSync service UUID over SDP and accept exactly one RFCOMM connection
/// from a bonded Android device — and what latency/throughput does the link deliver?
/// This is evidence collection only; it makes no READY claim and must not be shipped.
/// </summary>
internal static class Program
{
    /// <summary>SDP attribute id of the service name (Bluetooth SDP ServiceName, 0x0100).</summary>
    private const ushort SdpServiceNameAttributeId = 0x100;

    /// <summary>SDP type descriptor: text string with a 1-byte length ((4 &lt;&lt; 3) | 5).</summary>
    private const byte SdpServiceNameAttributeType = (4 << 3) | 5;

    private static async Task<int> Main(string[] args)
    {
        SpikeOptions options;
        try
        {
            options = SpikeOptions.Parse(args);
        }
        catch (SpikeHelpRequestedException)
        {
            Console.WriteLine(SpikeOptions.Usage);
            return 0;
        }
        catch (Exception exception) when (exception is ArgumentException or FormatException)
        {
            Console.WriteLine(exception.Message);
            Console.WriteLine(SpikeOptions.Usage);
            return 64;
        }

        SpikeLog.Result("spike", "windows-listener");
        SpikeLog.Result("mode", options.UseBt1 ? "bt1" : "raw");
        SpikeLog.Result("service_uuid", RfcommContract.ServiceUuid.ToString("D"));
        SpikeLog.Result("os_version", Environment.OSVersion.Version.ToString());
        SpikeLog.Result("is_64bit_os", Environment.Is64BitOperatingSystem);
        // A console `dotnet run` process has no MSIX identity by construction; running
        // this spike at all answers the "unpackaged WinRT access" question of phase 0.
        SpikeLog.Result("packaged", false);
        SpikeLog.Result("secret_fingerprint", options.SecretFingerprint());
        SpikeLog.Result("client_device_id", options.ClientDeviceId.ToString("D"));
        SpikeLog.Result("listener_device_id", options.ListenerDeviceId.ToString("D"));
        SpikeLog.Result("trust_epoch", options.TrustEpoch);

        try
        {
            return await RunListenerAsync(options);
        }
        catch (Exception exception)
        {
            SpikeLog.Result("session", "failed");
            SpikeLog.Result(
                "session_error",
                FormattableString.Invariant(
                    $"{exception.GetType().Name} hresult=0x{exception.HResult:x8} {exception.Message}"));
            SpikeLog.Result("exit", 1);
            return 1;
        }
    }

    private static async Task<int> RunListenerAsync(SpikeOptions options)
    {
        var adapter = await BluetoothAdapter.GetDefaultAsync();
        if (adapter is null)
        {
            SpikeLog.Result("adapter_present", false);
            SpikeLog.Info("No Bluetooth adapter found. Gate W1 fails on this machine.");
            SpikeLog.Result("exit", 2);
            return 2;
        }

        SpikeLog.Result("adapter_present", true);
        SpikeLog.Result("adapter_address", FormattableString.Invariant($"{adapter.BluetoothAddress:x12}"));
        SpikeLog.Result("adapter_classic_supported", adapter.IsClassicSupported);
        try
        {
            var adapterInfo = await DeviceInformation.CreateFromIdAsync(adapter.DeviceId);
            SpikeLog.Result("adapter_name", adapterInfo.Name);
        }
        catch (Exception exception)
        {
            SpikeLog.Result("adapter_name", FormattableString.Invariant($"unavailable ({exception.GetType().Name})"));
        }

        var radio = await adapter.GetRadioAsync();
        SpikeLog.Result("radio_state", radio.State.ToString());
        if (radio.State != RadioState.On)
        {
            SpikeLog.Info("The Bluetooth radio is not on; advertising will likely fail. Turn it on and rerun.");
        }

        // === The central phase 0 question: WinRT SDP publication from an unpackaged app.
        RfcommServiceProvider provider;
        try
        {
            provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(RfcommContract.ServiceUuid));
            SpikeLog.Result("winrt_rfcomm_provider", "ok");
        }
        catch (Exception exception)
        {
            SpikeLog.Result("winrt_rfcomm_provider", "failed");
            SpikeLog.Result(
                "winrt_rfcomm_provider_error",
                FormattableString.Invariant(
                    $"{exception.GetType().Name} hresult=0x{exception.HResult:x8} {exception.Message}"));
            SpikeLog.Info("Gate W1 fails on this machine: record the error above in the report.");
            SpikeLog.Result("exit", 3);
            return 3;
        }

        using var listener = new StreamSocketListener();
        var firstConnection = new TaskCompletionSource<StreamSocket>(TaskCreationOptions.RunContinuationsAsynchronously);
        listener.ConnectionReceived += (_, eventArgs) =>
        {
            if (!firstConnection.TrySetResult(eventArgs.Socket))
            {
                // ADR 0005 limit table: single session, second connection refused.
                SpikeLog.Result("extra_connection_rejected", true);
                eventArgs.Socket.Dispose();
            }
        };

        await listener.BindServiceNameAsync(
            provider.ServiceId.AsString(),
            SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);

        using (var writer = new DataWriter())
        {
            var serviceNameBytes = Encoding.UTF8.GetBytes(RfcommContract.ServiceName);
            writer.WriteByte(SdpServiceNameAttributeType);
            writer.WriteByte((byte)serviceNameBytes.Length);
            writer.WriteBytes(serviceNameBytes);
            provider.SdpRawAttributes.Add(SdpServiceNameAttributeId, writer.DetachBuffer());
        }

        provider.StartAdvertising(listener, options.Discoverable);
        SpikeLog.Result("sdp_published", true);
        SpikeLog.Result("discoverable", options.Discoverable);
        SpikeLog.Info(FormattableString.Invariant(
            $"Listening for ONE RFCOMM connection (timeout {options.AcceptTimeoutSeconds}s). Start the Android spike now."));

        StreamSocket socket;
        try
        {
            using var acceptTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(options.AcceptTimeoutSeconds));
            socket = await firstConnection.Task.WaitAsync(acceptTimeout.Token);
        }
        catch (OperationCanceledException)
        {
            SpikeLog.Result("connection_accepted", false);
            SpikeLog.Info("No connection arrived before the timeout.");
            provider.StopAdvertising();
            SpikeLog.Result("exit", 4);
            return 4;
        }

        SpikeLog.Result("connection_accepted", true);
        SpikeLog.Result("accepted_at_utc", DateTimeOffset.UtcNow.ToString("O"));
        SpikeLog.Result("remote_host", socket.Information.RemoteHostName?.DisplayName ?? "unknown");

        using (socket)
        {
            var input = socket.InputStream.AsStreamForRead();
            var output = socket.OutputStream.AsStreamForWrite();
            await using (input.ConfigureAwait(false))
            await using (output.ConfigureAwait(false))
            {
                using var session = new SpikeSession(options, input, output);
                using var sessionTimeout = new CancellationTokenSource(TimeSpan.FromMinutes(15));
                await session.RunAsync(sessionTimeout.Token);
            }
        }

        provider.StopAdvertising();
        SpikeLog.Result("exit", 0);
        return 0;
    }
}

#if WINDOWS10_0_19041_0_OR_GREATER
using System.Threading.Channels;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace ClipSync.Peer.Bluetooth;

/// <summary>
/// WinRT implementation of the RFCOMM listener seam: publishes the ClipSync SDP record
/// via <see cref="RfcommServiceProvider"/> and accepts connections with a
/// <see cref="StreamSocketListener"/> that requires an authenticated, encrypted link —
/// only OS-bonded devices can even open the socket, and bt1 authenticates the ClipSync
/// pairing on top of that. Compiled only for the Windows TFM; everything above the
/// <see cref="IRfcommServer"/> seam stays portable and unit-testable.
/// </summary>
public sealed class RfcommServer : IRfcommServer
{
    /// <summary>SDP attribute id for the service name, per the Bluetooth SDP specification.</summary>
    private const uint ServiceNameAttributeId = 0x100;

    /// <summary>SDP attribute type descriptor: text string (type 4) with a one-byte length (size index 5).</summary>
    private const byte ServiceNameAttributeType = (4 << 3) | 5;

    private RfcommServiceProvider? provider;
    private StreamSocketListener? listener;
    private Channel<StreamSocket> pending = CreatePendingChannel();
    private bool advertising;

    public async ValueTask StartAsync(CancellationToken cancellationToken)
    {
        if (provider is not null)
        {
            throw new InvalidOperationException("The RFCOMM server is already running.");
        }

        pending = CreatePendingChannel();
        // Fails when the adapter is absent or the radio is off; the host surfaces that
        // instead of pretending the fallback is armed.
        var created = await RfcommServiceProvider
            .CreateAsync(RfcommServiceId.FromUuid(RfcommContract.ServiceUuid))
            .AsTask(cancellationToken)
            .ConfigureAwait(false);

        var socketListener = new StreamSocketListener();
        socketListener.ConnectionReceived += OnConnectionReceived;
        try
        {
            await socketListener
                .BindServiceNameAsync(
                    created.ServiceId.AsString(),
                    SocketProtectionLevel.BluetoothEncryptionWithAuthentication)
                .AsTask(cancellationToken)
                .ConfigureAwait(false);
            WriteServiceNameAttribute(created);
            // The peers are already bonded through Windows settings, so the radio does not
            // need to become generally discoverable for the SDP record to resolve.
            created.StartAdvertising(socketListener, radioDiscoverable: false);
        }
        catch
        {
            socketListener.ConnectionReceived -= OnConnectionReceived;
            socketListener.Dispose();
            throw;
        }

        provider = created;
        listener = socketListener;
        advertising = true;
    }

    public async ValueTask<IRfcommConnection> AcceptAsync(CancellationToken cancellationToken)
    {
        var socket = await pending.Reader.ReadAsync(cancellationToken).ConfigureAwait(false);
        return new RfcommSocketConnection(socket);
    }

    public ValueTask StopAsync(CancellationToken cancellationToken)
    {
        if (advertising)
        {
            advertising = false;
            provider?.StopAdvertising();
        }

        if (listener is not null)
        {
            listener.ConnectionReceived -= OnConnectionReceived;
            listener.Dispose();
            listener = null;
        }

        provider = null;
        pending.Writer.TryComplete();
        while (pending.Reader.TryRead(out var socket))
        {
            socket.Dispose();
        }

        return ValueTask.CompletedTask;
    }

    public async ValueTask DisposeAsync() => await StopAsync(CancellationToken.None).ConfigureAwait(false);

    private void OnConnectionReceived(StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args)
    {
        if (!pending.Writer.TryWrite(args.Socket))
        {
            args.Socket.Dispose();
        }
    }

    private static Channel<StreamSocket> CreatePendingChannel() =>
        Channel.CreateBounded<StreamSocket>(new BoundedChannelOptions(capacity: 4)
        {
            FullMode = BoundedChannelFullMode.DropWrite
        });

    private static void WriteServiceNameAttribute(RfcommServiceProvider serviceProvider)
    {
        var writer = new DataWriter { UnicodeEncoding = UnicodeEncoding.Utf8 };
        writer.WriteByte(ServiceNameAttributeType);
        writer.WriteByte((byte)RfcommContract.ServiceName.Length);
        writer.WriteString(RfcommContract.ServiceName);
        serviceProvider.SdpRawAttributes.Add(ServiceNameAttributeId, writer.DetachBuffer());
    }

    private sealed class RfcommSocketConnection : IRfcommConnection
    {
        private readonly StreamSocket socket;

        public RfcommSocketConnection(StreamSocket socket)
        {
            this.socket = socket;
            Stream = new DuplexSocketStream(
                socket.InputStream.AsStreamForRead(),
                socket.OutputStream.AsStreamForWrite());
            RemoteAddress = socket.Information.RemoteHostName?.RawName ?? "unknown";
        }

        public Stream Stream { get; }

        public string RemoteAddress { get; }

        public ValueTask DisposeAsync()
        {
            Stream.Dispose();
            socket.Dispose();
            return ValueTask.CompletedTask;
        }
    }

    /// <summary>Joins the socket's two one-way WinRT streams into the duplex stream bt1 expects.</summary>
    private sealed class DuplexSocketStream(Stream input, Stream output) : Stream
    {
        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => true;

        public override long Length => throw new NotSupportedException();

        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }

        public override int Read(byte[] buffer, int offset, int count) => input.Read(buffer, offset, count);

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            input.ReadAsync(buffer, cancellationToken);

        public override void Write(byte[] buffer, int offset, int count) => output.Write(buffer, offset, count);

        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default) =>
            output.WriteAsync(buffer, cancellationToken);

        public override void Flush() => output.Flush();

        public override Task FlushAsync(CancellationToken cancellationToken) => output.FlushAsync(cancellationToken);

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                input.Dispose();
                output.Dispose();
            }

            base.Dispose(disposing);
        }
    }
}
#endif

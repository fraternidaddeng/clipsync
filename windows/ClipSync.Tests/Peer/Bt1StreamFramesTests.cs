using System.Buffers.Binary;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Bluetooth;

namespace ClipSync.Tests.Peer;

public sealed class Bt1StreamFramesTests
{
    [Fact]
    public async Task HandshakeFramesRoundTripIncludingMultibyteUtf8()
    {
        var (client, listener) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (client)
        using (listener)
        {
            const string payload = """{"kind":"bt1_client_hello","note":"手机端"}""";
            await Bt1StreamFrames.WriteHandshakeFrameAsync(client, payload, CancellationToken.None);
            var read = await Bt1StreamFrames.ReadHandshakePayloadAsync(listener, CancellationToken.None);
            Assert.Equal(payload, read);
        }
    }

    [Fact]
    public async Task CleanCloseBeforeAnyFrameReadsAsNull()
    {
        var (client, listener) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (listener)
        {
            client.Dispose();
            Assert.Null(await Bt1StreamFrames.ReadHandshakePayloadAsync(listener, CancellationToken.None));
            Assert.Null(await Bt1StreamFrames.ReadEncryptedPayloadAsync(listener, CancellationToken.None));
        }
    }

    [Fact]
    public async Task OversizeDeclaredHandshakeLengthIsFrameTooLargeBeforeAnyAllocation()
    {
        var (client, listener) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (client)
        using (listener)
        {
            var prefix = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(prefix, Bt1Frames.MaxHandshakePayloadLength + 1);
            await client.WriteAsync(prefix);
            var failure = await Assert.ThrowsAsync<Bt1FramingException>(
                async () => await Bt1StreamFrames.ReadHandshakePayloadAsync(listener, CancellationToken.None));
            Assert.Equal(Bt1ErrorCodes.FrameTooLarge, failure.ErrorCode);
        }
    }

    [Theory]
    [InlineData(0u)]
    [InlineData(1u)]
    public async Task UndersizeDeclaredHandshakeLengthIsASchemaViolation(uint declared)
    {
        var (client, listener) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (client)
        using (listener)
        {
            var prefix = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(prefix, declared);
            await client.WriteAsync(prefix);
            var failure = await Assert.ThrowsAsync<Bt1FramingException>(
                async () => await Bt1StreamFrames.ReadHandshakePayloadAsync(listener, CancellationToken.None));
            Assert.Equal(Bt1ErrorCodes.SchemaViolation, failure.ErrorCode);
        }
    }

    [Fact]
    public async Task TruncatedPayloadIsAnEndOfStreamFailureNotAHang()
    {
        var (client, listener) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (listener)
        {
            var prefix = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(prefix, 100);
            await client.WriteAsync(prefix);
            await client.WriteAsync(new byte[10]);
            client.Dispose();
            await Assert.ThrowsAsync<EndOfStreamException>(
                async () => await Bt1StreamFrames.ReadHandshakePayloadAsync(listener, CancellationToken.None));
        }
    }

    [Theory]
    [InlineData(0u)]
    [InlineData(16u)]
    [InlineData((uint)Bt1Frames.MaxEncryptedPayloadLength + 1)]
    [InlineData(uint.MaxValue)]
    public async Task EncryptedDeclaredLengthOutsideTheWindowIsFatalWithoutDecryption(uint declared)
    {
        var (client, listener) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (client)
        using (listener)
        {
            var prefix = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(prefix, declared);
            await client.WriteAsync(prefix);
            var failure = await Assert.ThrowsAsync<Bt1FramingException>(
                async () => await Bt1StreamFrames.ReadEncryptedPayloadAsync(listener, CancellationToken.None));
            Assert.Equal(Bt1ErrorCodes.FrameTooLarge, failure.ErrorCode);
        }
    }
}

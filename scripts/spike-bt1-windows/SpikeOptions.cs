using System.Globalization;
using System.Security.Cryptography;

namespace ClipSync.Spike.Bt1Windows;

/// <summary>
/// Command-line options of the phase 0 Windows spike listener. The defaults mirror the
/// Android spike (Bt1SpikeDefaults.kt) exactly so both sides work out of the box with
/// zero typing on the phone. The default secret is a PUBLIC, spike-only value — it
/// proves interoperability of the bt1 math, not confidentiality; never reuse it as, or
/// derive it from, a real ClipSync pair secret.
/// </summary>
internal sealed record SpikeOptions(
    bool UseBt1,
    byte[] PairSecret,
    Guid ClientDeviceId,
    Guid ListenerDeviceId,
    long TrustEpoch,
    int AcceptTimeoutSeconds,
    bool Discoverable)
{
    /// <summary>Spike-only shared secret (32 bytes as hex). Public by design.</summary>
    public const string DefaultSecretHex =
        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    /// <summary>Fixed spike device identity of the Android (client) side.</summary>
    public const string DefaultClientDeviceId = "11111111-1111-4111-8111-111111111111";

    /// <summary>Fixed spike device identity of the Windows (listener) side.</summary>
    public const string DefaultListenerDeviceId = "22222222-2222-4222-8222-222222222222";

    public static string Usage =>
        """
        ClipSync phase 0 Bluetooth RFCOMM spike listener (SPIKE ONLY, never shipped).

        Usage: dotnet run --project scripts/spike-bt1-windows -- [options]

          --mode <bt1|raw>            bt1 = handshake + encrypted frames (default);
                                      raw = plain length-prefixed frames (link isolation).
          --secret-hex <64 hex>       Spike-only 32-byte shared secret. Default is a fixed
                                      public value matching the Android spike.
          --client-device-id <uuid>   Expected Android spike device id (default fixed).
          --listener-device-id <uuid> This device's spike id (default fixed).
          --trust-epoch <n>           Trust epoch, >= 1 (default 1).
          --accept-timeout <seconds>  How long to wait for the one connection (default 300).
          --discoverable              Also make the radio discoverable while advertising.
                                      Default off: bonded devices connect without discovery,
                                      which is exactly what phase 2 relies on.
          --help                      Show this help.
        """;

    public static SpikeOptions Parse(string[] args)
    {
        var useBt1 = true;
        var secretHex = DefaultSecretHex;
        var clientId = Guid.Parse(DefaultClientDeviceId);
        var listenerId = Guid.Parse(DefaultListenerDeviceId);
        long trustEpoch = 1;
        var acceptTimeoutSeconds = 300;
        var discoverable = false;

        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--mode":
                    useBt1 = NextValue(args, ref i) switch
                    {
                        "bt1" => true,
                        "raw" => false,
                        var other => throw new ArgumentException($"Unknown --mode value '{other}'; use bt1 or raw.")
                    };
                    break;
                case "--secret-hex":
                    secretHex = NextValue(args, ref i);
                    break;
                case "--client-device-id":
                    clientId = Guid.Parse(NextValue(args, ref i));
                    break;
                case "--listener-device-id":
                    listenerId = Guid.Parse(NextValue(args, ref i));
                    break;
                case "--trust-epoch":
                    trustEpoch = long.Parse(NextValue(args, ref i), CultureInfo.InvariantCulture);
                    break;
                case "--accept-timeout":
                    acceptTimeoutSeconds = int.Parse(NextValue(args, ref i), CultureInfo.InvariantCulture);
                    break;
                case "--discoverable":
                    discoverable = true;
                    break;
                case "--help" or "-h" or "/?":
                    throw new SpikeHelpRequestedException();
                default:
                    throw new ArgumentException($"Unknown argument '{args[i]}'. Use --help.");
            }
        }

        var secret = Convert.FromHexString(secretHex);
        if (secret.Length != 32)
        {
            throw new ArgumentException("--secret-hex must decode to exactly 32 bytes (64 hex characters).");
        }

        if (trustEpoch < 1)
        {
            throw new ArgumentException("--trust-epoch must be at least 1.");
        }

        return new SpikeOptions(useBt1, secret, clientId, listenerId, trustEpoch, acceptTimeoutSeconds, discoverable);
    }

    /// <summary>
    /// First 4 bytes of SHA-256(secret) as hex: lets both sides confirm they typed the
    /// same secret without ever printing the secret itself.
    /// </summary>
    public string SecretFingerprint() =>
        Convert.ToHexString(SHA256.HashData(PairSecret).AsSpan(0, 4)).ToLowerInvariant();

    private static string NextValue(string[] args, ref int index)
    {
        if (index + 1 >= args.Length)
        {
            throw new ArgumentException($"Missing value after '{args[index]}'.");
        }

        index++;
        return args[index];
    }
}

/// <summary>Thrown by option parsing when the user asked for --help.</summary>
internal sealed class SpikeHelpRequestedException : Exception;

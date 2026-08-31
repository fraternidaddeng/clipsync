using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

/// <summary>
/// The wireless half of <see cref="PrivilegedHostAssistant"/>: exact command building
/// (argv tokens, never a shell string) and honest outcome mapping, all through the fake
/// runner so no test ever launches adb.
/// </summary>
public sealed class WirelessAdbAssistantTests
{
    private static readonly WirelessAdbEndpoint PairEndpoint = new("192.168.1.10", 37123);

    [Fact]
    public void PairCommandIsExactArgvTokens()
    {
        var args = WirelessAdbCommands.Pair(PairEndpoint, "123456");

        Assert.Equal("pair 192.168.1.10:37123 123456", string.Join(' ', args));
    }

    [Fact]
    public void ConnectCommandIsExactArgvTokens()
    {
        var args = WirelessAdbCommands.Connect(new WirelessAdbEndpoint("192.168.1.10", 40331));

        Assert.Equal("connect 192.168.1.10:40331", string.Join(' ', args));
    }

    [Fact]
    public void Ipv6EndpointIsRebracketedInTheCommand()
    {
        var args = WirelessAdbCommands.Connect(new WirelessAdbEndpoint("fe80::1", 40331));

        Assert.Equal("connect [fe80::1]:40331", string.Join(' ', args));
    }

    [Fact]
    public async Task PairRunsAdbPairAndReadsBackSuccess()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["pair", "192.168.1.10:37123", "123456"],
            new AdbCommandResult(0, "Successfully paired to 192.168.1.10:37123 [guid=adb-X]\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var outcome = await assistant.PairWirelessAsync(PairEndpoint, "123456");

        Assert.True(outcome.Succeeded);
        Assert.Contains(runner.Invocations, args => string.Join(' ', args) == "pair 192.168.1.10:37123 123456");
    }

    [Fact]
    public async Task PairOnMissingAdbShortCircuitsWithoutRunning()
    {
        var runner = new FakeAdbRunner { Available = false };
        var assistant = new PrivilegedHostAssistant(runner);

        var outcome = await assistant.PairWirelessAsync(PairEndpoint, "123456");

        Assert.Equal(AdbPairStatus.AdbFailed, outcome.Status);
        Assert.Empty(runner.Invocations);
    }

    [Fact]
    public async Task ConnectRunsAdbConnectAndReadsBackTheVerdict()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["connect", "192.168.1.10:40331"],
            new AdbCommandResult(0, "failed to connect to '192.168.1.10:40331': Connection refused\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var outcome = await assistant.ConnectWirelessAsync(new WirelessAdbEndpoint("192.168.1.10", 40331));

        Assert.Equal(AdbConnectStatus.Refused, outcome.Status);
    }

    [Fact]
    public void DisconnectCommandIsExactArgvTokens()
    {
        var args = WirelessAdbCommands.Disconnect(new WirelessAdbEndpoint("192.168.1.10", 40331));

        Assert.Equal("disconnect 192.168.1.10:40331", string.Join(' ', args));
    }

    [Fact]
    public async Task VerifiedConnectPassesAFreshConnectionThroughWithoutProbing()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["connect", "192.168.1.10:40331"],
            new AdbCommandResult(0, "connected to 192.168.1.10:40331\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var result = await assistant.ConnectWirelessVerifiedAsync(new WirelessAdbEndpoint("192.168.1.10", 40331));

        Assert.Equal(AdbConnectStatus.Connected, result.Outcome.Status);
        Assert.False(result.RecoveredStaleSession);
        Assert.Equal(["connect 192.168.1.10:40331"], runner.Invocations.Select(args => string.Join(' ', args)));
    }

    [Fact]
    public async Task VerifiedConnectAcceptsAlreadyConnectedWhenTheDeviceListBacksItUp()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["connect", "192.168.1.10:40331"],
            new AdbCommandResult(0, "already connected to 192.168.1.10:40331\n", string.Empty));
        runner.OnArgs(
            ["devices", "-l"],
            new AdbCommandResult(
                0,
                "List of devices attached\n192.168.1.10:40331     device product:p model:Pixel_8 device:d\n",
                string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var result = await assistant.ConnectWirelessVerifiedAsync(new WirelessAdbEndpoint("192.168.1.10", 40331));

        Assert.Equal(AdbConnectStatus.AlreadyConnected, result.Outcome.Status);
        Assert.False(result.RecoveredStaleSession);
        Assert.DoesNotContain(runner.Invocations, args => args[0] == "disconnect");
    }

    [Fact]
    public async Task VerifiedConnectRedialsAStaleAlreadyConnectedSession()
    {
        // adb keeps answering "already connected" from its session table after wireless port
        // drift, while `adb devices` shows the entry offline. The verified connect must not
        // report that as success: it disconnects the stale entry and dials once more.
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["connect", "192.168.1.10:40331"],
            new AdbCommandResult(0, "already connected to 192.168.1.10:40331\n", string.Empty),
            new AdbCommandResult(0, "connected to 192.168.1.10:40331\n", string.Empty));
        runner.OnArgs(
            ["devices", "-l"],
            new AdbCommandResult(0, "List of devices attached\n192.168.1.10:40331     offline\n", string.Empty));
        runner.OnArgs(
            ["disconnect", "192.168.1.10:40331"],
            new AdbCommandResult(0, "disconnected 192.168.1.10:40331\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var result = await assistant.ConnectWirelessVerifiedAsync(new WirelessAdbEndpoint("192.168.1.10", 40331));

        Assert.Equal(AdbConnectStatus.Connected, result.Outcome.Status);
        Assert.True(result.RecoveredStaleSession);
        Assert.Equal(
            ["connect 192.168.1.10:40331", "devices -l", "disconnect 192.168.1.10:40331", "connect 192.168.1.10:40331"],
            runner.Invocations.Select(args => string.Join(' ', args)));
    }

    [Fact]
    public async Task VerifiedConnectRedialsWhenTheClaimedSessionVanishedFromTheList()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["connect", "192.168.1.10:40331"],
            new AdbCommandResult(0, "already connected to 192.168.1.10:40331\n", string.Empty),
            new AdbCommandResult(0, "failed to connect to '192.168.1.10:40331': Connection refused\n", string.Empty));
        runner.OnArgs(
            ["devices", "-l"],
            new AdbCommandResult(0, "List of devices attached\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var result = await assistant.ConnectWirelessVerifiedAsync(new WirelessAdbEndpoint("192.168.1.10", 40331));

        // The honest final verdict is the redial's failure — never the stale "already connected".
        Assert.Equal(AdbConnectStatus.Refused, result.Outcome.Status);
        Assert.True(result.RecoveredStaleSession);
        Assert.Contains(runner.Invocations, args => string.Join(' ', args) == "disconnect 192.168.1.10:40331");
    }

    [Fact]
    public async Task MdnsCheckMapsWorkingDaemonToTrue()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(["mdns", "check"], new AdbCommandResult(0, "mdns daemon version [10970003]\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        Assert.True(await assistant.CheckMdnsSupportAsync());
    }

    [Theory]
    [InlineData(0, "ERROR: mdns discovery disabled\n")]
    [InlineData(1, "ERROR: mdns discovery disabled\n")]
    public async Task MdnsCheckMapsDisabledDiscoveryToFalse(int exitCode, string stdout)
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(["mdns", "check"], new AdbCommandResult(exitCode, stdout, string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        Assert.False(await assistant.CheckMdnsSupportAsync());
    }

    [Fact]
    public async Task DiscoverPairingEndpointMatchesOnlyOurExactServiceName()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["mdns", "services"],
            new AdbCommandResult(
                0,
                "List of discovered mdns services\n" +
                "studio-abc\t_adb-tls-pairing._tcp.\t192.168.1.99:37000\n" +
                "clipsync-k3m9p2q7rt\t_adb-tls-pairing._tcp.\t192.168.1.10:37123\n",
                string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var endpoint = await assistant.DiscoverPairingEndpointAsync("clipsync-k3m9p2q7rt");

        // Android Studio's concurrent pairing session must never be grabbed.
        Assert.Equal(new WirelessAdbEndpoint("192.168.1.10", 37123), endpoint);
    }

    [Fact]
    public async Task DiscoverPairingEndpointIsNullBeforeThePhoneScans()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["mdns", "services"],
            new AdbCommandResult(0, "List of discovered mdns services\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        Assert.Null(await assistant.DiscoverPairingEndpointAsync("clipsync-k3m9p2q7rt"));
    }

    [Fact]
    public async Task DiscoverConnectEndpointPrefersThePairedHost()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["mdns", "services"],
            new AdbCommandResult(
                0,
                "List of discovered mdns services\n" +
                "adb-OTHER-x\t_adb-tls-connect._tcp.\t192.168.1.99:40000\n" +
                "adb-RF8N123456-a\t_adb-tls-connect._tcp.\t192.168.1.10:40331\n",
                string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var endpoint = await assistant.DiscoverConnectEndpointAsync("192.168.1.10");

        Assert.Equal(new WirelessAdbEndpoint("192.168.1.10", 40331), endpoint);
    }

    [Fact]
    public async Task DiscoverConnectEndpointFallsBackToTheFirstAnnouncement()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["mdns", "services"],
            new AdbCommandResult(
                0,
                "adb-OTHER-x\t_adb-tls-connect._tcp.\t192.168.1.99:40000\n",
                string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var endpoint = await assistant.DiscoverConnectEndpointAsync("10.0.0.1");

        Assert.Equal(new WirelessAdbEndpoint("192.168.1.99", 40000), endpoint);
    }

    [Fact]
    public async Task FailedMdnsListingYieldsEmptyNotStale()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(["mdns", "services"], new AdbCommandResult(1, string.Empty, "adb: unknown command mdns\n"));
        var assistant = new PrivilegedHostAssistant(runner);

        Assert.Empty(await assistant.ListMdnsServicesAsync());
        Assert.Null(await assistant.DiscoverConnectEndpointAsync());
    }

    private sealed class FakeAdbRunner : IAdbRunner
    {
        private readonly List<(string[] Args, Queue<AdbCommandResult> Results, AdbCommandResult Last)> responses = new();

        public bool Available { get; set; } = true;

        public List<string[]> Invocations { get; } = new();

        public bool IsAvailable => Available;

        public string LocationDescription => Available ? "adb: /fake/adb" : "adb not found";

        /// <summary>One or more results for repeated identical invocations; the last one repeats.</summary>
        public void OnArgs(string[] args, params AdbCommandResult[] results) =>
            responses.Add((args, new Queue<AdbCommandResult>(results), results[^1]));

        public Task<AdbCommandResult> RunAsync(
            IReadOnlyList<string> arguments,
            TimeSpan? timeout = null,
            CancellationToken cancellationToken = default)
        {
            var args = arguments.ToArray();
            Invocations.Add(args);
            foreach (var (candidate, queued, last) in responses)
            {
                if (candidate.SequenceEqual(args))
                {
                    return Task.FromResult(queued.Count > 0 ? queued.Dequeue() : last);
                }
            }

            return Task.FromResult(new AdbCommandResult(0, string.Empty, string.Empty));
        }
    }
}

using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class PrivilegedHostAssistantTests
{
    [Fact]
    public async Task AdbUnavailableShortCircuitsWithoutRunningAnything()
    {
        var runner = new FakeAdbRunner { Available = false };
        var assistant = new PrivilegedHostAssistant(runner);

        var probe = await assistant.ProbeAsync();

        Assert.Equal(PrivilegedHostAvailability.AdbUnavailable, probe.Availability);
        Assert.Empty(runner.Invocations);
    }

    [Fact]
    public async Task ReadyDeviceIsDetectedAndHostRunningIsReadBack()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(["devices", "-l"], new AdbCommandResult(0, "List of devices attached\nSERIAL1\tdevice model:Pixel\n", string.Empty));
        // pgrep prints a pid → host running.
        runner.OnArgs(["-s", "SERIAL1", "shell", "pgrep", "-f", "clipsync_priv_server"], new AdbCommandResult(0, "9182\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var probe = await assistant.ProbeAsync();

        Assert.Equal(PrivilegedHostAvailability.DeviceReady, probe.Availability);
        Assert.Equal("SERIAL1", probe.Target?.Serial);
        Assert.True(probe.HostRunning);
    }

    [Fact]
    public async Task ReadyDeviceWithNoHostProcessReportsHostStopped()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(["devices", "-l"], new AdbCommandResult(0, "List of devices attached\nSERIAL1\tdevice\n", string.Empty));
        // pgrep with no match exits 1 and prints nothing.
        runner.OnArgs(["-s", "SERIAL1", "shell", "pgrep", "-f", "clipsync_priv_server"], new AdbCommandResult(1, string.Empty, string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var probe = await assistant.ProbeAsync();

        Assert.Equal(PrivilegedHostAvailability.DeviceReady, probe.Availability);
        Assert.False(probe.HostRunning);
    }

    [Fact]
    public async Task UnauthorizedDeviceMapsToUnauthorizedAndOffersNoTarget()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(["devices", "-l"], new AdbCommandResult(0, "List of devices attached\nSERIAL1\tunauthorized\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        var probe = await assistant.ProbeAsync();

        Assert.Equal(PrivilegedHostAvailability.DeviceUnauthorized, probe.Availability);
        Assert.Null(probe.Target);
    }

    [Fact]
    public async Task NoDevicesMapsToNoDevice()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(["devices", "-l"], new AdbCommandResult(0, "List of devices attached\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner);

        Assert.Equal(PrivilegedHostAvailability.NoDevice, (await assistant.ProbeAsync()).Availability);
    }

    [Fact]
    public async Task StartRunsTheOnDeviceScriptWithTheSerialAndReadsBackSuccess()
    {
        var runner = new FakeAdbRunner();
        runner.OnArgs(
            ["-s", "SERIAL1", "shell", "sh", PrivilegedHostPaths.ScriptPath],
            new AdbCommandResult(0, "info: spawned\n", string.Empty));
        // Post-spawn verification: the host process is confirmed running.
        runner.OnArgs(
            ["-s", "SERIAL1", "shell", "pgrep", "-f", "clipsync_priv_server"],
            new AdbCommandResult(0, "9182\n", string.Empty));
        var assistant = new PrivilegedHostAssistant(runner, delay: (_, _) => Task.CompletedTask);

        var outcome = await assistant.StartAsync("SERIAL1");

        Assert.True(outcome.Succeeded);
        Assert.Equal(PrivilegedHostStartStatus.Started, outcome.Status);
        Assert.Contains(runner.Invocations, args => args.SequenceEqual(new[] { "-s", "SERIAL1", "shell", "sh", PrivilegedHostPaths.ScriptPath }));
    }

    [Fact]
    public async Task StartOnMissingAdbReportsAdbFailureWithoutRunning()
    {
        var runner = new FakeAdbRunner { Available = false };
        var assistant = new PrivilegedHostAssistant(runner);

        var outcome = await assistant.StartAsync("SERIAL1");

        Assert.Equal(PrivilegedHostStartStatus.AdbFailed, outcome.Status);
        Assert.Empty(runner.Invocations);
    }

    private sealed class FakeAdbRunner : IAdbRunner
    {
        private readonly List<(string[] Args, AdbCommandResult Result)> responses = new();

        public bool Available { get; set; } = true;

        public List<string[]> Invocations { get; } = new();

        public bool IsAvailable => Available;

        public string LocationDescription => Available ? "adb: /fake/adb" : "adb not found";

        public void OnArgs(string[] args, AdbCommandResult result) => responses.Add((args, result));

        public Task<AdbCommandResult> RunAsync(
            IReadOnlyList<string> arguments,
            TimeSpan? timeout = null,
            CancellationToken cancellationToken = default)
        {
            var args = arguments.ToArray();
            Invocations.Add(args);
            foreach (var (candidate, result) in responses)
            {
                if (candidate.SequenceEqual(args))
                {
                    return Task.FromResult(result);
                }
            }

            return Task.FromResult(new AdbCommandResult(0, string.Empty, string.Empty));
        }
    }
}

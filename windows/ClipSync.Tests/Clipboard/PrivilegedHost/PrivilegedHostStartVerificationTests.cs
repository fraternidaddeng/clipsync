using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

/// <summary>
/// 特权直读 start honesty: "info: spawned" is printed before the host could crash or the wireless
/// transport could drop, so the assistant re-checks (read-only pgrep, retried) that the host
/// actually stayed up before it reports success. A host that launched and died is reported as
/// <see cref="PrivilegedHostStartStatus.SpawnedButNotDetected"/>, never as a rosy "已发送启动命令".
/// </summary>
public sealed class PrivilegedHostStartVerificationTests
{
    private static readonly string[] StartArgs =
        ["-s", "SERIAL1", "shell", "sh", PrivilegedHostPaths.ScriptPath];

    private static readonly string[] PgrepArgs =
        ["-s", "SERIAL1", "shell", "pgrep", "-f", "clipsync_priv_server"];

    private static readonly AdbCommandResult Spawned = new(0, "info: spawned\n", string.Empty);
    private static readonly AdbCommandResult HostRunning = new(0, "9182\n", string.Empty);

    // pgrep with no match exits 1 and prints nothing: a definite "not running".
    private static readonly AdbCommandResult HostStopped = new(1, string.Empty, string.Empty);

    // pgrep that could not run at all (exit 2, no output): the probe is unknown, not "dead".
    private static readonly AdbCommandResult ProbeUnknown = new(2, string.Empty, "pgrep: cannot open");

    [Fact]
    public async Task SpawnedHostThatNeverAppearsIsReportedNotDetected()
    {
        var runner = new SequencedAdbRunner();
        runner.OnArgs(StartArgs, Spawned);
        runner.OnArgs(PgrepArgs, HostStopped); // definitely absent on every retry

        var assistant = NoWaitAssistant(runner, attempts: 4);
        var outcome = await assistant.StartAsync("SERIAL1");

        Assert.False(outcome.Succeeded);
        Assert.Equal(PrivilegedHostStartStatus.SpawnedButNotDetected, outcome.Status);
        // The retry budget was actually exercised (start + N pgrep checks).
        Assert.Equal(4, runner.CountFor(PgrepArgs));
    }

    [Fact]
    public async Task SpawnedHostAppearingAfterARetryIsSuccess()
    {
        var runner = new SequencedAdbRunner();
        runner.OnArgs(StartArgs, Spawned);
        // Cold boot: absent on the first two checks, then the host is up.
        runner.OnArgsSequence(PgrepArgs, HostStopped, HostStopped, HostRunning);

        var assistant = NoWaitAssistant(runner, attempts: 6);
        var outcome = await assistant.StartAsync("SERIAL1");

        Assert.True(outcome.Succeeded);
        Assert.Equal(PrivilegedHostStartStatus.Started, outcome.Status);
        // Stopped polling the moment it saw the host running — three checks, not the full six.
        Assert.Equal(3, runner.CountFor(PgrepArgs));
    }

    [Fact]
    public async Task SpawnedHostConfirmedOnTheFirstCheckDoesNotWaitFurther()
    {
        var runner = new SequencedAdbRunner();
        runner.OnArgs(StartArgs, Spawned);
        runner.OnArgs(PgrepArgs, HostRunning);

        var assistant = NoWaitAssistant(runner, attempts: 6);
        var outcome = await assistant.StartAsync("SERIAL1");

        Assert.Equal(PrivilegedHostStartStatus.Started, outcome.Status);
        Assert.Equal(1, runner.CountFor(PgrepArgs));
    }

    [Fact]
    public async Task ProbeThatCannotRunLeavesTheSpawnSuccessStanding()
    {
        // Never a definite "stopped" — only unknowns. Inventing a failure here would be the mirror
        // image of the false-success bug: honesty means the spawn result stands.
        var runner = new SequencedAdbRunner();
        runner.OnArgs(StartArgs, Spawned);
        runner.OnArgs(PgrepArgs, ProbeUnknown);

        var assistant = NoWaitAssistant(runner, attempts: 3);
        var outcome = await assistant.StartAsync("SERIAL1");

        Assert.Equal(PrivilegedHostStartStatus.Started, outcome.Status);
    }

    [Fact]
    public async Task AScriptFailureSkipsVerificationEntirely()
    {
        var runner = new SequencedAdbRunner();
        runner.OnArgs(StartArgs, new AdbCommandResult(7, "fatal: apk not found\n", string.Empty));

        var assistant = NoWaitAssistant(runner, attempts: 6);
        var outcome = await assistant.StartAsync("SERIAL1");

        Assert.Equal(PrivilegedHostStartStatus.ScriptFailed, outcome.Status);
        Assert.Equal("fatal: apk not found", outcome.Reason);
        Assert.Equal(0, runner.CountFor(PgrepArgs));
    }

    private static PrivilegedHostAssistant NoWaitAssistant(SequencedAdbRunner runner, int attempts) =>
        new(runner, delay: (_, _) => Task.CompletedTask, startVerifyAttempts: attempts, startVerifyInterval: TimeSpan.Zero);

    /// <summary>A fake adb that can return a different result per successive call to the same args.</summary>
    private sealed class SequencedAdbRunner : IAdbRunner
    {
        private readonly List<(string[] Args, Queue<AdbCommandResult> Results, AdbCommandResult Last)> responses = new();

        public bool IsAvailable => true;

        public string LocationDescription => "adb: /fake/adb";

        public List<string[]> Invocations { get; } = new();

        public void OnArgs(string[] args, AdbCommandResult result) => OnArgsSequence(args, result);

        public void OnArgsSequence(string[] args, params AdbCommandResult[] results)
        {
            var queue = new Queue<AdbCommandResult>(results);
            responses.Add((args, queue, results[^1]));
        }

        public int CountFor(string[] args) => Invocations.Count(i => i.SequenceEqual(args));

        public Task<AdbCommandResult> RunAsync(IReadOnlyList<string> arguments, CancellationToken cancellationToken = default)
        {
            var args = arguments.ToArray();
            Invocations.Add(args);
            foreach (var (candidate, results, last) in responses)
            {
                if (candidate.SequenceEqual(args))
                {
                    // Successive calls drain the queue; once exhausted the last result repeats.
                    return Task.FromResult(results.Count > 0 ? results.Dequeue() : last);
                }
            }

            return Task.FromResult(new AdbCommandResult(0, string.Empty, string.Empty));
        }
    }
}

using System.Net;
using System.Text;
using ClipSync.Core.Update;

namespace ClipSync.Tests.Update;

public sealed class GitHubReleaseClientTests
{
    [Fact]
    public async Task FetchLatestParsesASuccessfulLatestPayload()
    {
        var json =
            """
            {"tag_name":"v0.4.0","html_url":"https://example.test/r","assets":[
              {"name":"ClipSync-windows-x64.zip","browser_download_url":"https://example.test/w.zip","size":12,
               "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
            """;
        using var client = new GitHubReleaseClient(
            currentVersion: "0.3.0",
            handler: new ScriptedHandler(("GET", json)),
            latestUri: new Uri("https://example.test/latest"));
        var release = await client.FetchLatestAsync();
        Assert.Equal("0.4.0", release.VersionLabel);
        Assert.NotNull(release.FindPayload(UpdatePlatform.Windows));
    }

    [Fact]
    public async Task FetchLatestSurfacesANonSuccessStatus()
    {
        using var client = new GitHubReleaseClient(
            handler: new ScriptedHandler(("GET", "rate limited", HttpStatusCode.Forbidden)),
            latestUri: new Uri("https://example.test/latest"));
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => client.FetchLatestAsync());
        Assert.Contains("403", error.Message);
    }

    [Fact]
    public async Task ResolveSha256FallsBackToTheSidecarWhenDigestIsMissing()
    {
        var latest =
            """
            {"tag_name":"v0.2.0","assets":[
              {"name":"ClipSync-android.apk","browser_download_url":"https://example.test/a.apk","size":1},
              {"name":"ClipSync-android.apk.sha256","browser_download_url":"https://example.test/a.sha256","size":80}]}
            """;
        using var client = new GitHubReleaseClient(
            handler: new ScriptedHandler(
                ("GET", latest),
                ("GET", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb *ClipSync-android.apk\n")),
            latestUri: new Uri("https://example.test/latest"));
        var release = await client.FetchLatestAsync();
        var payload = release.FindPayload(UpdatePlatform.Android)!;
        var hex = await client.ResolveSha256Async(release, payload);
        Assert.Equal("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", hex);
    }

    [Fact]
    public void VerifySha256RejectsAMismatchAndAcceptsAMatch()
    {
        var bytes = Encoding.UTF8.GetBytes("hello");
        using var stream = new MemoryStream(bytes);
        var hex = GitHubReleaseClient.ComputeSha256Hex(stream);
        GitHubReleaseClient.VerifySha256(stream, hex);
        Assert.Throws<InvalidOperationException>(() => GitHubReleaseClient.VerifySha256(stream, "ab" + hex[2..]));
    }

    [Fact]
    public void ComputeSha256HexDoesNotSignExtendHighBytes()
    {
        // 00 80 FF — a naive "%02x".format(signedByte) would emit ffffff80 / ffffffff.
        using var stream = new MemoryStream([0x00, 0x80, 0xFF]);
        Assert.Equal(
            "5240672d7b51756b829ad0ef8d9468b7a078afa2f410484fd3892dab47becb72",
            GitHubReleaseClient.ComputeSha256Hex(stream));
    }

    private sealed class ScriptedHandler : HttpMessageHandler
    {
        private readonly Queue<(string Method, string Body, HttpStatusCode Status)> replies;

        public ScriptedHandler(params (string Method, string Body, HttpStatusCode Status)[] replies)
        {
            this.replies = new Queue<(string, string, HttpStatusCode)>(replies);
        }

        public ScriptedHandler(params (string Method, string Body)[] replies)
            : this(replies.Select(item => (item.Method, item.Body, HttpStatusCode.OK)).ToArray())
        {
        }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            Assert.True(replies.Count > 0, $"unexpected {request.Method} {request.RequestUri}");
            var (method, body, status) = replies.Dequeue();
            Assert.Equal(method, request.Method.Method);
            return Task.FromResult(new HttpResponseMessage(status)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
            });
        }
    }
}

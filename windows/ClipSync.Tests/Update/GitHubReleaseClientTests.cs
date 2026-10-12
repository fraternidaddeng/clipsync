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
    public async Task FetchLatestNeverFallsBackToAThirdPartyMirror()
    {
        // Release metadata carries the trusted digest, so it must only come from GitHub.
        var official =
            $"https://api.github.com/repos/{GitHubReleaseClient.DefaultOwner}/{GitHubReleaseClient.DefaultRepo}{GitHubReleaseClient.LatestPath}";
        var mirror = GitHubUrlMirrors.Prefixes[0] + official;
        var json =
            """{"tag_name":"v0.4.0","html_url":"https://github.com/fraternidaddeng/clipsync/releases/tag/v0.4.0","assets":[]}""";
        var seen = new List<string>();
        using var client = new GitHubReleaseClient(
            currentVersion: "0.3.0",
            handler: new UrlMapHandler(seen)
            {
                [official] = (HttpStatusCode.Forbidden, "rate limited"),
                [mirror] = (HttpStatusCode.OK, json),
            },
            latestUri: new Uri(official));
        await Assert.ThrowsAsync<HttpRequestException>(() => client.FetchLatestAsync());
        Assert.Equal(new[] { official }, seen);
    }

    [Fact]
    public async Task Sha256SidecarIsNeverFetchedFromAMirror()
    {
        var payloadUrl = "https://github.com/fraternidaddeng/clipsync/releases/download/v0.4.0/clip.zip";
        var sidecarUrl = payloadUrl + ".sha256";
        var seen = new List<string>();
        using var client = new GitHubReleaseClient(
            handler: new UrlMapHandler(seen)
            {
                [sidecarUrl] = (HttpStatusCode.BadGateway, "down"),
                [GitHubUrlMirrors.Prefixes[0] + sidecarUrl] = (HttpStatusCode.OK, new string('a', 64)),
            });
        var payload = new ReleaseAsset("clip.zip", payloadUrl, 3, null);
        var release = new GitHubLatestRelease("v0.4.0", "https://github.com/x", [payload, new ReleaseAsset("clip.zip.sha256", sidecarUrl, 64, null)]);
        await Assert.ThrowsAsync<HttpRequestException>(() => client.ResolveSha256Async(release, payload));
        Assert.Equal(new[] { sidecarUrl }, seen);
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

    [Fact]
    public async Task DownloadRetryReplacesAPartialPayloadInsteadOfAppending()
    {
        var official = "https://github.com/fraternidaddeng/clipsync/releases/download/v0.4.0/clip.zip";
        var mirror = GitHubUrlMirrors.Prefixes[0] + official;
        var asset = new ReleaseAsset(
            "ClipSync-windows-x64.zip",
            official,
            3,
            null);
        using var client = new GitHubReleaseClient(
            handler: new UrlMapHandlerWithContent(seen: [],
                (official, new FailingContent("bad")),
                (mirror, new ByteArrayContent("ok!"u8.ToArray()))));
        using var destination = new MemoryStream();
        destination.Write("prefix"u8);
        destination.Position = destination.Length;

        await client.DownloadAsync(asset, destination);

        Assert.Equal("prefixok!", Encoding.UTF8.GetString(destination.ToArray()));
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

    private sealed class UrlMapHandler : HttpMessageHandler
    {
        private readonly List<string> seen;
        private readonly Dictionary<string, (HttpStatusCode Status, string Body)> replies = new(StringComparer.Ordinal);

        public UrlMapHandler(List<string> seen)
        {
            this.seen = seen;
        }

        public (HttpStatusCode Status, string Body) this[string url]
        {
            set => replies[url] = value;
        }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var url = request.RequestUri?.ToString() ?? "";
            seen.Add(url);
            Assert.True(replies.ContainsKey(url), $"unexpected {url}");
            var (status, body) = replies[url];
            return Task.FromResult(new HttpResponseMessage(status)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
            });
        }
    }

    private sealed class UrlMapHandlerWithContent : HttpMessageHandler
    {
        private readonly Dictionary<string, HttpContent> replies;
        private readonly List<string> seen;

        public UrlMapHandlerWithContent(List<string> seen, params (string Url, HttpContent Content)[] replies)
        {
            this.seen = seen;
            this.replies = replies.ToDictionary(item => item.Url, item => item.Content, StringComparer.Ordinal);
        }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var url = request.RequestUri?.ToString() ?? "";
            seen.Add(url);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = replies[url],
            });
        }
    }

    private sealed class FailingContent : HttpContent
    {
        private readonly byte[] prefix;

        public FailingContent(string prefix) => this.prefix = Encoding.UTF8.GetBytes(prefix);

        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context)
        {
            throw new NotSupportedException();
        }

        protected override bool TryComputeLength(out long length)
        {
            length = prefix.Length;
            return true;
        }

        protected override Task<Stream> CreateContentReadStreamAsync()
        {
            return Task.FromResult<Stream>(new ThrowingReadStream(prefix));
        }
    }

    private sealed class ThrowingReadStream(byte[] prefix) : MemoryStream(prefix)
    {
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (Position >= Length)
            {
                throw new IOException("simulated truncated response");
            }

            return base.Read(buffer, offset, count);
        }

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            if (Position >= Length)
            {
                throw new IOException("simulated truncated response");
            }

            return base.ReadAsync(buffer, cancellationToken);
        }
    }
}

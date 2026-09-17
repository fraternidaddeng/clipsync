using ClipSync.Core.Update;

namespace ClipSync.Tests.Update;

public sealed class GitHubUrlMirrorsTests
{
    [Fact]
    public void GithubApiUrlTriesOfficialThenEachPrefix()
    {
        var official =
            $"https://api.github.com/repos/{GitHubReleaseClient.DefaultOwner}/{GitHubReleaseClient.DefaultRepo}{GitHubReleaseClient.LatestPath}";
        var candidates = GitHubUrlMirrors.Candidates(official);
        Assert.Equal(official, candidates[0]);
        Assert.Equal(
            new[] { official }.Concat(GitHubUrlMirrors.Prefixes.Select(prefix => prefix + official)),
            candidates);
    }

    [Fact]
    public void InjectedTestHostsStaySingleCandidate()
    {
        Assert.Equal(
            "https://example.test/latest",
            Assert.Single(GitHubUrlMirrors.Candidates("https://example.test/latest")));
        Assert.False(GitHubUrlMirrors.IsMirrorable("https://example.test/latest"));
    }

    [Fact]
    public void AlreadyPrefixedUrlIsNotWrappedAgain()
    {
        var wrapped = GitHubUrlMirrors.Prefixes[0]
            + "https://github.com/fraternidaddeng/clipsync/releases/download/v0.4.0/ClipSync-android.apk";
        Assert.Equal(wrapped, Assert.Single(GitHubUrlMirrors.Candidates(wrapped)));
    }
}

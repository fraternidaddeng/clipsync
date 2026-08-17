using System.Security.Cryptography;
using ClipSync.Core.Security;

namespace ClipSync.App.Security;

/// <summary>
/// DPAPI (CurrentUser) implementation of <see cref="ISecretProtector"/>: pair secrets and
/// the TLS private key never touch disk unprotected, and only this Windows user can recover them.
/// </summary>
public sealed class DpapiSecretProtector : ISecretProtector
{
    private static readonly byte[] Entropy = "ClipSync/v1/dpapi"u8.ToArray();

    public byte[] Protect(ReadOnlySpan<byte> plaintext) =>
        ProtectedData.Protect(plaintext.ToArray(), Entropy, DataProtectionScope.CurrentUser);

    public byte[] Unprotect(ReadOnlySpan<byte> ciphertext) =>
        ProtectedData.Unprotect(ciphertext.ToArray(), Entropy, DataProtectionScope.CurrentUser);
}

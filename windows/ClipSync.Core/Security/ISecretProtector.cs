namespace ClipSync.Core.Security;

/// <summary>
/// Protects secrets at rest. Windows uses DPAPI (CurrentUser); tests use a reversible fake.
/// Implementations must never log inputs or outputs.
/// </summary>
public interface ISecretProtector
{
    byte[] Protect(ReadOnlySpan<byte> plaintext);

    byte[] Unprotect(ReadOnlySpan<byte> ciphertext);
}

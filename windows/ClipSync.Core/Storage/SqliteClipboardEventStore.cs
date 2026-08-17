using System.Collections.Concurrent;
using System.Data;
using ClipSync.Core.Clipboard;
using Microsoft.Data.Sqlite;

namespace ClipSync.Core.Storage;

public sealed partial class SqliteClipboardEventStore : IClipboardEventStore, IAsyncDisposable
{
    public const int SchemaVersion = 2;
    private const int MaximumQueryLimit = 2_000;
    private const int BusyTimeoutMilliseconds = 30_000;
    private const string TextKind = "text";
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> InitializationLocks =
        new(StringComparer.OrdinalIgnoreCase);

    private readonly string databasePath;
    private readonly string localDeviceId;
    private readonly string connectionString;
    private readonly IStorageFaultInjector? faultInjector;
    private bool initialized;
    private bool disposed;

    public SqliteClipboardEventStore(
        string databasePath,
        string localDeviceId,
        IStorageFaultInjector? faultInjector = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(localDeviceId);

        this.databasePath = Path.GetFullPath(databasePath);
        this.localDeviceId = localDeviceId;
        this.faultInjector = faultInjector;
        connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = this.databasePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = true,
            DefaultTimeout = BusyTimeoutMilliseconds / 1_000
        }.ToString();
    }

    public async ValueTask InitializeAsync(CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        if (initialized)
        {
            return;
        }

        var initializationLock = InitializationLocks.GetOrAdd(databasePath, static _ => new SemaphoreSlim(1, 1));
        await initializationLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (initialized)
            {
                return;
            }

            var parentDirectory = Path.GetDirectoryName(databasePath);
            if (!string.IsNullOrEmpty(parentDirectory))
            {
                Directory.CreateDirectory(parentDirectory);
            }

            await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
            await ExecuteNonQueryAsync(connection, null, "PRAGMA journal_mode = WAL;", cancellationToken).ConfigureAwait(false);

            var schemaVersion = await ReadScalarInt64Async(
                connection,
                null,
                "PRAGMA user_version;",
                cancellationToken).ConfigureAwait(false);
            if (schemaVersion > SchemaVersion)
            {
                throw new InvalidOperationException(
                    $"Database schema version {schemaVersion} is newer than supported version {SchemaVersion}.");
            }

            await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
                IsolationLevel.Serializable,
                cancellationToken).ConfigureAwait(false);
            try
            {
                await ApplyOrderedMigrationsAsync(connection, transaction, schemaVersion, cancellationToken)
                    .ConfigureAwait(false);

                await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
                throw;
            }

            initialized = true;
        }
        finally
        {
            initializationLock.Release();
        }
    }

    public async ValueTask<StoredClipboardEvent> StoreAsync(
        AcceptedClipboardContent content,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        var eventId = Guid.NewGuid();
        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);

        try
        {
            var originSequence = await AllocateSequenceAsync(connection, transaction, cancellationToken).ConfigureAwait(false);
            await InjectFaultAsync(StorageFaultPoint.AfterSequenceAllocated, cancellationToken).ConfigureAwait(false);

            await using (var command = connection.CreateCommand())
            {
                command.Transaction = transaction;
                command.CommandText = """
                    INSERT INTO clips (
                        event_id,
                        origin_device_id,
                        origin_seq,
                        kind,
                        content,
                        content_hash,
                        source_app,
                        created_at,
                        expires_at,
                        deleted_at)
                    VALUES (
                        $event_id,
                        $origin_device_id,
                        $origin_seq,
                        $kind,
                        $content,
                        $content_hash,
                        $source_app,
                        $created_at,
                        NULL,
                        NULL);
                    """;
                command.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
                command.Parameters.AddWithValue("$origin_device_id", localDeviceId);
                command.Parameters.AddWithValue("$origin_seq", originSequence);
                command.Parameters.AddWithValue("$kind", TextKind);
                command.Parameters.AddWithValue("$content", content.Text);
                command.Parameters.AddWithValue("$content_hash", content.ContentHash);
                command.Parameters.AddWithValue("$source_app", (object?)content.SourceProcess ?? DBNull.Value);
                command.Parameters.AddWithValue("$created_at", content.CapturedAt.ToUnixTimeMilliseconds());
                await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            await AdvanceLocalReceiveStateAsync(connection, transaction, originSequence, cancellationToken).ConfigureAwait(false);
            await EnqueueOutboxFanOutAsync(
                connection,
                transaction,
                eventId,
                localDeviceId,
                originSequence,
                excludedPeerId: null,
                cancellationToken).ConfigureAwait(false);

            await InjectFaultAsync(StorageFaultPoint.BeforeCommit, cancellationToken).ConfigureAwait(false);
            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            return new StoredClipboardEvent(eventId, originSequence, content);
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    public async ValueTask<IReadOnlyList<ClipboardHistoryEntry>> SearchAsync(
        ClipboardHistoryQuery query,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(query);
        ValidateQuery(query);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT
                event_id,
                origin_device_id,
                origin_seq,
                content,
                content_hash,
                source_app,
                created_at,
                expires_at,
                deleted_at
            FROM clips
            WHERE deleted_at IS NULL
              AND ($search IS NULL OR content LIKE $pattern ESCAPE '\' COLLATE NOCASE)
            ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
            LIMIT $limit OFFSET $offset;
            """;
        var search = string.IsNullOrEmpty(query.SearchText) ? null : query.SearchText;
        command.Parameters.AddWithValue("$search", (object?)search ?? DBNull.Value);
        command.Parameters.AddWithValue("$pattern", search is null ? DBNull.Value : $"%{EscapeLikePattern(search)}%");
        command.Parameters.AddWithValue("$limit", query.Limit);
        command.Parameters.AddWithValue("$offset", query.Offset);

        return await ReadEntriesAsync(command, cancellationToken).ConfigureAwait(false);
    }

    public async ValueTask<ClipboardHistoryEntry?> GetByIdAsync(
        Guid eventId,
        bool includeDeleted = false,
        CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT
                event_id,
                origin_device_id,
                origin_seq,
                content,
                content_hash,
                source_app,
                created_at,
                expires_at,
                deleted_at
            FROM clips
            WHERE event_id = $event_id
              AND ($include_deleted = 1 OR deleted_at IS NULL)
            LIMIT 1;
            """;
        command.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
        command.Parameters.AddWithValue("$include_deleted", includeDeleted ? 1 : 0);

        var entries = await ReadEntriesAsync(command, cancellationToken).ConfigureAwait(false);
        return entries.Count == 0 ? null : entries[0];
    }

    public async ValueTask<bool> DeleteAsync(
        Guid eventId,
        DateTimeOffset deletedAt,
        CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = CreateSoftDeleteCommand(connection, transaction: null, deletedAt);
        command.CommandText += " AND event_id = $event_id;";
        command.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
        return await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) == 1;
    }

    public async ValueTask<int> ClearAsync(
        DateTimeOffset deletedAt,
        CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = CreateSoftDeleteCommand(connection, transaction: null, deletedAt);
        command.CommandText += ";";
        return await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    public async ValueTask<int> CleanupAsync(
        ClipboardRetentionPolicy policy,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(policy);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            await using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
                WITH cleanup_candidates AS (
                    SELECT event_id
                    FROM clips
                    WHERE deleted_at IS NULL
                      AND created_at < $oldest_created_at
                    UNION
                    SELECT event_id
                    FROM (
                        SELECT event_id
                        FROM clips
                        WHERE deleted_at IS NULL
                        ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
                        LIMIT -1 OFFSET $maximum_entries
                    )
                )
                UPDATE clips
                SET content = '',
                    content_hash = '',
                    source_app = NULL,
                    deleted_at = $deleted_at,
                    terminal_reason = 'expired'
                WHERE deleted_at IS NULL
                  AND event_id IN (SELECT event_id FROM cleanup_candidates);
                """;
            command.Parameters.AddWithValue("$oldest_created_at", (now - policy.MaximumAge).ToUnixTimeMilliseconds());
            command.Parameters.AddWithValue("$maximum_entries", policy.MaximumEntries);
            command.Parameters.AddWithValue("$deleted_at", now.ToUnixTimeMilliseconds());
            var removed = await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            return removed;
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    public async ValueTask<string?> GetSettingAsync(
        string key,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT value FROM settings WHERE key = $key;";
        command.Parameters.AddWithValue("$key", key);
        return await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false) as string;
    }

    public async ValueTask SetSettingAsync(
        string key,
        string value,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        ArgumentNullException.ThrowIfNull(value);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO settings (key, value)
            VALUES ($key, $value)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value;
            """;
        command.Parameters.AddWithValue("$key", key);
        command.Parameters.AddWithValue("$value", value);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    public async ValueTask<DatabaseState> ReadDatabaseStateAsync(CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var journalMode = await ReadScalarStringAsync(
            connection,
            null,
            "PRAGMA journal_mode;",
            cancellationToken).ConfigureAwait(false);
        var foreignKeys = await ReadScalarInt64Async(
            connection,
            null,
            "PRAGMA foreign_keys;",
            cancellationToken).ConfigureAwait(false);
        var schemaVersion = await ReadScalarInt64Async(
            connection,
            null,
            "PRAGMA user_version;",
            cancellationToken).ConfigureAwait(false);
        return new DatabaseState(journalMode, foreignKeys == 1, checked((int)schemaVersion));
    }

    public ValueTask DisposeAsync()
    {
        disposed = true;
        return ValueTask.CompletedTask;
    }

    private async ValueTask EnsureInitializedAsync(CancellationToken cancellationToken)
    {
        ThrowIfDisposed();
        if (!initialized)
        {
            await InitializeAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private async ValueTask<SqliteConnection> OpenConnectionAsync(CancellationToken cancellationToken)
    {
        var connection = new SqliteConnection(connectionString);
        try
        {
            await connection.OpenAsync(cancellationToken).ConfigureAwait(false);
            await ExecuteNonQueryAsync(
                connection,
                null,
                $"PRAGMA foreign_keys = ON; PRAGMA busy_timeout = {BusyTimeoutMilliseconds};",
                cancellationToken).ConfigureAwait(false);
            return connection;
        }
        catch
        {
            await connection.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    private async ValueTask<long> AllocateSequenceAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO local_sequences (device_id, next_seq)
            VALUES ($device_id, 2)
            ON CONFLICT(device_id) DO UPDATE SET next_seq = next_seq + 1
            RETURNING next_seq - 1;
            """;
        command.Parameters.AddWithValue("$device_id", localDeviceId);
        var value = await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false);
        return Convert.ToInt64(value, System.Globalization.CultureInfo.InvariantCulture);
    }

    private ValueTask InjectFaultAsync(StorageFaultPoint point, CancellationToken cancellationToken) =>
        faultInjector?.InjectAsync(point, cancellationToken) ?? ValueTask.CompletedTask;

    /// <summary>
    /// Applies schema steps in version order. Never DROP TABLE clips.
    /// A future bump must add a named step here in the same change as <see cref="SchemaVersion"/>.
    /// A gap with no step refuses to open rather than creating a fresh file.
    /// </summary>
    private static async ValueTask ApplyOrderedMigrationsAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        long schemaVersion,
        CancellationToken cancellationToken)
    {
        for (var version = 1; version <= SchemaVersion; version++)
        {
            if (SchemaMigrations.All(step => step.TargetVersion != version))
            {
                throw new InvalidOperationException(
                    $"Schema version {version} has no migration step. Refusing to open so history cannot be dropped.");
            }
        }

        foreach (var step in SchemaMigrations)
        {
            if (schemaVersion < step.TargetVersion)
            {
                await step.Apply(connection, transaction, cancellationToken).ConfigureAwait(false);
            }
        }

        if (schemaVersion < SchemaVersion)
        {
            await ExecuteNonQueryAsync(
                connection,
                transaction,
                $"PRAGMA user_version = {SchemaVersion};",
                cancellationToken).ConfigureAwait(false);
        }
    }

    private static readonly SchemaMigrationStep[] SchemaMigrations =
    [
        new(1, CreateBaselineSchemaAsync),
        new(2, ApplySyncSchemaAsync),
    ];

    private readonly record struct SchemaMigrationStep(
        int TargetVersion,
        Func<SqliteConnection, SqliteTransaction, CancellationToken, ValueTask> Apply);

    private static async ValueTask CreateBaselineSchemaAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string sql = """
            CREATE TABLE clips (
                event_id TEXT PRIMARY KEY,
                origin_device_id TEXT NOT NULL,
                origin_seq INTEGER NOT NULL CHECK (origin_seq >= 1),
                kind TEXT NOT NULL CHECK (kind = 'text'),
                content TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                source_app TEXT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                deleted_at INTEGER,
                UNIQUE(origin_device_id, origin_seq)
            );

            CREATE INDEX clips_visible_history_idx
                ON clips(deleted_at, created_at DESC, origin_seq DESC);

            CREATE TABLE local_sequences (
                device_id TEXT PRIMARY KEY,
                next_seq INTEGER NOT NULL DEFAULT 1 CHECK (next_seq >= 1)
            );

            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """;
        await ExecuteNonQueryAsync(connection, transaction, sql, cancellationToken).ConfigureAwait(false);
    }

    private static async ValueTask ApplySyncSchemaAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string sql = """
            ALTER TABLE clips ADD COLUMN terminal_reason TEXT
                CHECK (terminal_reason IN ('local_only', 'deleted', 'expired', 'policy_filtered', 'not_found'));

            UPDATE clips SET terminal_reason = 'deleted' WHERE deleted_at IS NOT NULL;

            CREATE TABLE devices (
                device_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                platform TEXT NOT NULL CHECK (platform IN ('windows', 'android')),
                certificate_fingerprint TEXT NOT NULL,
                pair_secret_protected TEXT NOT NULL,
                trust_epoch INTEGER NOT NULL DEFAULT 1 CHECK (trust_epoch >= 1),
                created_at INTEGER NOT NULL,
                last_seen_at INTEGER,
                revoked_at INTEGER
            );

            CREATE TABLE origin_receive_state (
                origin_device_id TEXT PRIMARY KEY,
                contiguous_seq INTEGER NOT NULL DEFAULT 0 CHECK (contiguous_seq >= 0),
                received_ranges TEXT NOT NULL DEFAULT '[]'
            );

            INSERT INTO origin_receive_state (origin_device_id, contiguous_seq)
            SELECT device_id, next_seq - 1 FROM local_sequences WHERE next_seq > 1;

            CREATE TABLE peer_cursors (
                peer_id TEXT NOT NULL,
                origin_device_id TEXT NOT NULL,
                contiguous_seq INTEGER NOT NULL DEFAULT 0 CHECK (contiguous_seq >= 0),
                received_ranges TEXT NOT NULL DEFAULT '[]',
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (peer_id, origin_device_id)
            );

            CREATE TABLE outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                origin_device_id TEXT NOT NULL,
                origin_seq INTEGER NOT NULL CHECK (origin_seq >= 1),
                state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'announced')),
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error_code TEXT,
                UNIQUE (peer_id, origin_device_id, origin_seq)
            );

            CREATE INDEX outbox_peer_state_idx ON outbox(peer_id, state, next_attempt_at);
            """;
        await ExecuteNonQueryAsync(connection, transaction, sql, cancellationToken).ConfigureAwait(false);
    }

    private async ValueTask AdvanceLocalReceiveStateAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        long originSequence,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO origin_receive_state (origin_device_id, contiguous_seq)
            VALUES ($device_id, $seq)
            ON CONFLICT(origin_device_id) DO UPDATE SET contiguous_seq = excluded.contiguous_seq;
            """;
        command.Parameters.AddWithValue("$device_id", localDeviceId);
        command.Parameters.AddWithValue("$seq", originSequence);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private static SqliteCommand CreateSoftDeleteCommand(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        DateTimeOffset deletedAt)
    {
        var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            UPDATE clips
            SET content = '',
                content_hash = '',
                source_app = NULL,
                deleted_at = $deleted_at,
                terminal_reason = 'deleted'
            WHERE deleted_at IS NULL
            """;
        command.Parameters.AddWithValue("$deleted_at", deletedAt.ToUnixTimeMilliseconds());
        return command;
    }

    private static async ValueTask<IReadOnlyList<ClipboardHistoryEntry>> ReadEntriesAsync(
        SqliteCommand command,
        CancellationToken cancellationToken)
    {
        var entries = new List<ClipboardHistoryEntry>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            entries.Add(new ClipboardHistoryEntry(
                Guid.Parse(reader.GetString(0)),
                reader.GetString(1),
                reader.GetInt64(2),
                reader.GetString(3),
                reader.GetString(4),
                reader.IsDBNull(5) ? null : reader.GetString(5),
                DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(6)),
                reader.IsDBNull(7) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(7)),
                reader.IsDBNull(8) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(8))));
        }

        return entries;
    }

    [System.Diagnostics.CodeAnalysis.SuppressMessage(
        "Security",
        "CA2100:Review SQL queries for security vulnerabilities",
        Justification = "Helper assigns CommandText from this store's compile-time SQL/PRAGMA (literals or integer constants). Callers never pass user clipboard/query text as SQL.")]
    private static async ValueTask ExecuteNonQueryAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        string sql,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    [System.Diagnostics.CodeAnalysis.SuppressMessage(
        "Security",
        "CA2100:Review SQL queries for security vulnerabilities",
        Justification = "Helper assigns CommandText from this store's compile-time SQL/PRAGMA (literals or integer constants). Callers never pass user clipboard/query text as SQL.")]
    private static async ValueTask<long> ReadScalarInt64Async(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        string sql,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        var result = await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false);
        return Convert.ToInt64(result, System.Globalization.CultureInfo.InvariantCulture);
    }

    [System.Diagnostics.CodeAnalysis.SuppressMessage(
        "Security",
        "CA2100:Review SQL queries for security vulnerabilities",
        Justification = "Helper assigns CommandText from this store's compile-time SQL/PRAGMA (literals or integer constants). Callers never pass user clipboard/query text as SQL.")]
    private static async ValueTask<string> ReadScalarStringAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        string sql,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        var result = await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false);
        return Convert.ToString(result, System.Globalization.CultureInfo.InvariantCulture) ?? string.Empty;
    }

    private static string EscapeLikePattern(string value) =>
        value.Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("%", "\\%", StringComparison.Ordinal)
            .Replace("_", "\\_", StringComparison.Ordinal);

    private static void ValidateQuery(ClipboardHistoryQuery query)
    {
        if (query.Limit is <= 0 or > MaximumQueryLimit)
        {
            throw new ArgumentOutOfRangeException(nameof(query), $"Limit must be between 1 and {MaximumQueryLimit}.");
        }

        if (query.Offset < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(query), "Offset cannot be negative.");
        }
    }

    private void ThrowIfDisposed()
    {
        ObjectDisposedException.ThrowIf(disposed, this);
    }
}

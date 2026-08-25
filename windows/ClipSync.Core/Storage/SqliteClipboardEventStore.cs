using System.Collections.Concurrent;
using System.Data;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;
using Microsoft.Data.Sqlite;

namespace ClipSync.Core.Storage;

public sealed partial class SqliteClipboardEventStore : IClipboardEventStore, IAsyncDisposable
{
    public const int SchemaVersion = 5;
    private const int MaximumQueryLimit = 2_000;
    private const int BusyTimeoutMilliseconds = 30_000;
    private const string TextKind = "text";
    private const string ImageKind = "image";
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> InitializationLocks =
        new(StringComparer.OrdinalIgnoreCase);

    private readonly string databasePath;
    private readonly string localDeviceId;
    private readonly string connectionString;
    private readonly IStorageFaultInjector? faultInjector;
    private readonly MediaBlobStore media;
    private readonly SemaphoreSlim mediaLifecycle = new(1, 1);
    private static readonly TimeSpan BlobGcGrace = TimeSpan.FromMinutes(5);
    private bool initialized;
    private bool disposed;

    public SqliteClipboardEventStore(
        string databasePath,
        string localDeviceId,
        IStorageFaultInjector? faultInjector = null,
        MediaBlobStore? media = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(localDeviceId);

        this.databasePath = Path.GetFullPath(databasePath);
        this.localDeviceId = localDeviceId;
        this.faultInjector = faultInjector;
        this.media = media ?? new MediaBlobStore(MediaBlobStore.DefaultRootForDatabase(this.databasePath));
        connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = this.databasePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = true,
            DefaultTimeout = BusyTimeoutMilliseconds / 1_000
        }.ToString();
    }

    public MediaBlobStore Media => media;

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

            media.RecoverTemps(DateTimeOffset.UtcNow);
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

    public async ValueTask<StoredImageEvent> StoreImageAsync(
        AcceptedImageContent image,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(image);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        await mediaLifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
        var validated = media.CommitBytes(image.EncodedBytes, image.ContentHash);
        var eventId = Guid.NewGuid();
        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);

        try
        {
            var originSequence = await AllocateSequenceAsync(connection, transaction, cancellationToken).ConfigureAwait(false);
            await InjectFaultAsync(StorageFaultPoint.AfterSequenceAllocated, cancellationToken).ConfigureAwait(false);

            await InsertImageClipAsync(
                connection,
                transaction,
                eventId,
                localDeviceId,
                originSequence,
                validated,
                image.SourceProcess,
                image.CapturedAt,
                expiresAt: null,
                cancellationToken).ConfigureAwait(false);

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
            return new StoredImageEvent(eventId, originSequence, image with
            {
                ContentHash = validated.ContentHash,
                MimeType = validated.MimeType,
                EncodedBytes = image.EncodedBytes,
                PixelWidth = validated.PixelWidth,
                PixelHeight = validated.PixelHeight
            });
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
        }
        finally
        {
            mediaLifecycle.Release();
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
                c.event_id,
                c.origin_device_id,
                c.origin_seq,
                c.content,
                c.content_hash,
                c.source_app,
                c.created_at,
                c.expires_at,
                c.deleted_at,
                c.kind,
                b.mime_type,
                b.encoded_bytes,
                b.pixel_width,
                b.pixel_height
            FROM clips c
            LEFT JOIN clip_media m ON m.event_id = c.event_id
            LEFT JOIN media_blobs b ON b.content_hash = m.content_hash
            WHERE c.deleted_at IS NULL
              AND (c.expires_at IS NULL OR c.expires_at > $now)
              AND (
                    $search IS NULL
                    OR c.content LIKE $pattern ESCAPE '\' COLLATE NOCASE
                    OR (c.kind = 'image' AND (
                        'image' LIKE $pattern ESCAPE '\' COLLATE NOCASE
                        OR IFNULL(b.mime_type, '') LIKE $pattern ESCAPE '\' COLLATE NOCASE
                    ))
                  )
            ORDER BY c.created_at DESC, c.origin_seq DESC, c.origin_device_id ASC, c.event_id ASC
            LIMIT $limit OFFSET $offset;
            """;
        var search = string.IsNullOrEmpty(query.SearchText) ? null : query.SearchText;
        command.Parameters.AddWithValue("$search", (object?)search ?? DBNull.Value);
        command.Parameters.AddWithValue("$pattern", search is null ? DBNull.Value : $"%{EscapeLikePattern(search)}%");
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
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
                c.event_id,
                c.origin_device_id,
                c.origin_seq,
                c.content,
                c.content_hash,
                c.source_app,
                c.created_at,
                c.expires_at,
                c.deleted_at,
                c.kind,
                b.mime_type,
                b.encoded_bytes,
                b.pixel_width,
                b.pixel_height
            FROM clips c
            LEFT JOIN clip_media m ON m.event_id = c.event_id
            LEFT JOIN media_blobs b ON b.content_hash = m.content_hash
            WHERE c.event_id = $event_id
              AND ($include_deleted = 1 OR c.deleted_at IS NULL)
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
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            await using var command = CreateSoftDeleteCommand(connection, transaction, deletedAt);
            command.CommandText += " AND event_id = $event_id;";
            command.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
            var deleted = await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) == 1;
            if (deleted)
            {
                await DetachClipMediaAsync(connection, transaction, eventId.ToString("D"), cancellationToken)
                    .ConfigureAwait(false);
                string? origin = null;
                long seq = 0;
                await using (var lookup = connection.CreateCommand())
                {
                    lookup.Transaction = transaction;
                    lookup.CommandText = "SELECT origin_device_id, origin_seq FROM clips WHERE event_id = $event_id;";
                    lookup.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
                    await using var reader = await lookup.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
                    if (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
                    {
                        origin = reader.GetString(0);
                        seq = reader.GetInt64(1);
                    }
                }

                if (origin is not null)
                {
                    await EnqueueOutboxFanOutAsync(
                        connection,
                        transaction,
                        eventId,
                        origin,
                        seq,
                        excludedPeerId: null,
                        cancellationToken).ConfigureAwait(false);
                }
            }

            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            if (deleted)
            {
                await CollectUnreferencedBlobsAsync(cancellationToken).ConfigureAwait(false);
            }

            return deleted;
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    public async ValueTask<int> ClearAsync(
        DateTimeOffset deletedAt,
        CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            await using var command = CreateSoftDeleteCommand(connection, transaction, deletedAt);
            command.CommandText += ";";
            var removed = await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            await DetachOrphanedClipMediaAsync(connection, transaction, cancellationToken).ConfigureAwait(false);
            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            await CollectUnreferencedBlobsAsync(cancellationToken).ConfigureAwait(false);
            return removed;
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
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
                      AND (
                            created_at < $oldest_created_at
                            OR (expires_at IS NOT NULL AND expires_at <= $now)
                          )
                      AND event_id NOT IN (SELECT event_id FROM outbox)
                    UNION
                    SELECT event_id
                    FROM (
                        SELECT event_id
                        FROM clips
                        WHERE deleted_at IS NULL
                          AND (expires_at IS NULL OR expires_at > $now)
                          AND event_id NOT IN (SELECT event_id FROM outbox)
                        ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
                        LIMIT -1 OFFSET $maximum_entries
                    )
                )
                UPDATE clips
                SET content = CASE WHEN kind = 'image' THEN NULL ELSE '' END,
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
            command.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            var removed = await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            await using var hardDelete = connection.CreateCommand();
            hardDelete.Transaction = transaction;
            hardDelete.CommandText = """
                DELETE FROM clips
                WHERE deleted_at IS NOT NULL
                  AND deleted_at < $tombstone_cutoff
                  AND event_id NOT IN (SELECT event_id FROM outbox);
                """;
            hardDelete.Parameters.AddWithValue("$tombstone_cutoff", (now - policy.MaximumAge).ToUnixTimeMilliseconds());
            await hardDelete.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            await DetachOrphanedClipMediaAsync(connection, transaction, cancellationToken).ConfigureAwait(false);
            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            await CollectUnreferencedBlobsAsync(cancellationToken).ConfigureAwait(false);
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
        new(3, ApplyImageSchemaAsync),
        new(4, ApplyContentHashIndexAsync),
        new(5, ApplyDeviceAccentOverrideAsync),
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

    /// <summary>
    /// Adjacent v2→v3 step. Copies clips into a table that allows kind=image and
    /// NULL content, then adds media_blobs/clip_media. Never truncates history.
    /// </summary>
    private static async ValueTask ApplyImageSchemaAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string sql = """
            CREATE TABLE clips_v3 (
                event_id TEXT PRIMARY KEY,
                origin_device_id TEXT NOT NULL,
                origin_seq INTEGER NOT NULL CHECK (origin_seq >= 1),
                kind TEXT NOT NULL CHECK (kind IN ('text', 'image')),
                content TEXT,
                content_hash TEXT NOT NULL,
                source_app TEXT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                deleted_at INTEGER,
                terminal_reason TEXT
                    CHECK (terminal_reason IN (
                        'local_only', 'deleted', 'expired', 'policy_filtered',
                        'not_found', 'unsupported_media')),
                UNIQUE(origin_device_id, origin_seq),
                CHECK (
                    (kind = 'text' AND content IS NOT NULL)
                    OR (kind = 'image' AND content IS NULL)
                )
            );

            INSERT INTO clips_v3 (
                event_id, origin_device_id, origin_seq, kind, content, content_hash,
                source_app, created_at, expires_at, deleted_at, terminal_reason)
            SELECT
                event_id, origin_device_id, origin_seq, kind, content, content_hash,
                source_app, created_at, expires_at, deleted_at, terminal_reason
            FROM clips;

            DROP TABLE clips;
            ALTER TABLE clips_v3 RENAME TO clips;

            CREATE INDEX clips_visible_history_idx
                ON clips(deleted_at, created_at DESC, origin_seq DESC);

            CREATE TABLE media_blobs (
                content_hash TEXT PRIMARY KEY,
                mime_type TEXT NOT NULL CHECK (mime_type IN ('image/png', 'image/jpeg')),
                encoded_bytes INTEGER NOT NULL
                    CHECK (encoded_bytes >= 1 AND encoded_bytes <= 16777216),
                pixel_width INTEGER NOT NULL
                    CHECK (pixel_width >= 1 AND pixel_width <= 8192),
                pixel_height INTEGER NOT NULL
                    CHECK (pixel_height >= 1 AND pixel_height <= 8192),
                state TEXT NOT NULL CHECK (state IN ('ready', 'pending', 'failed')),
                created_at INTEGER NOT NULL,
                CHECK ((pixel_width * 1.0) * pixel_height <= 33554432)
            );

            CREATE TABLE clip_media (
                event_id TEXT PRIMARY KEY,
                content_hash TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'ready'
                    CHECK (state IN ('ready', 'pending', 'missing')),
                FOREIGN KEY (event_id) REFERENCES clips(event_id),
                FOREIGN KEY (content_hash) REFERENCES media_blobs(content_hash)
            );

            CREATE INDEX clip_media_hash_idx ON clip_media(content_hash);
            """;
        await ExecuteNonQueryAsync(connection, transaction, sql, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Adjacent v3→v4 step. Purely additive: an index on clips(content_hash) so the
    /// announce-time materialization lookup (<see cref="FindLiveContentByHashAsync"/>,
    /// run by the session engine for every announced text clip) stops scanning the
    /// whole table. The Android Room schema has carried this index since v1.
    /// </summary>
    private static async ValueTask ApplyContentHashIndexAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string sql = """
            CREATE INDEX IF NOT EXISTS clips_content_hash_idx ON clips(content_hash);
            """;
        await ExecuteNonQueryAsync(connection, transaction, sql, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Adjacent v4→v5 step (settings-roadmap P1#14 设备色手动改). Purely additive:
    /// a nullable per-device accent override (1..5 = charter dev-1..dev-5). NULL keeps
    /// the pairing-order default. The value belongs to the device identity, so revoke
    /// and re-pair upserts leave it alone.
    /// </summary>
    private static async ValueTask ApplyDeviceAccentOverrideAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string sql = """
            ALTER TABLE devices ADD COLUMN accent_override INTEGER
                CHECK (accent_override IS NULL OR accent_override BETWEEN 1 AND 5);
            """;
        await ExecuteNonQueryAsync(connection, transaction, sql, cancellationToken).ConfigureAwait(false);
    }

    private async ValueTask AdvanceLocalReceiveStateAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        long originSequence,
        CancellationToken cancellationToken)
    {
        await AdvanceRemoteReceiveStateAsync(
            connection,
            transaction,
            localDeviceId,
            originSequence,
            cancellationToken).ConfigureAwait(false);
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
            SET content = CASE WHEN kind = 'image' THEN NULL ELSE '' END,
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
            var kind = reader.FieldCount > 9 && !reader.IsDBNull(9) ? reader.GetString(9) : TextKind;
            entries.Add(new ClipboardHistoryEntry(
                Guid.Parse(reader.GetString(0)),
                reader.GetString(1),
                reader.GetInt64(2),
                reader.IsDBNull(3) ? string.Empty : reader.GetString(3),
                reader.GetString(4),
                reader.IsDBNull(5) ? null : reader.GetString(5),
                DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(6)),
                reader.IsDBNull(7) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(7)),
                reader.IsDBNull(8) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(8)),
                kind,
                reader.FieldCount > 10 && !reader.IsDBNull(10) ? reader.GetString(10) : null,
                reader.FieldCount > 11 && !reader.IsDBNull(11) ? reader.GetInt32(11) : null,
                reader.FieldCount > 12 && !reader.IsDBNull(12) ? reader.GetInt32(12) : null,
                reader.FieldCount > 13 && !reader.IsDBNull(13) ? reader.GetInt32(13) : null));
        }

        return entries;
    }

    private static async ValueTask InsertImageClipAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        Guid eventId,
        string originDeviceId,
        long originSeq,
        ValidatedImage image,
        string? sourceApp,
        DateTimeOffset createdAt,
        DateTimeOffset? expiresAt,
        CancellationToken cancellationToken)
    {
        await using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO clips (
                    event_id, origin_device_id, origin_seq, kind, content, content_hash,
                    source_app, created_at, expires_at, deleted_at, terminal_reason)
                VALUES (
                    $event_id, $origin, $seq, 'image', NULL, $hash,
                    $source_app, $created_at, $expires_at, NULL, NULL);
                """;
            insert.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
            insert.Parameters.AddWithValue("$origin", originDeviceId);
            insert.Parameters.AddWithValue("$seq", originSeq);
            insert.Parameters.AddWithValue("$hash", image.ContentHash);
            insert.Parameters.AddWithValue("$source_app", (object?)sourceApp ?? DBNull.Value);
            insert.Parameters.AddWithValue("$created_at", createdAt.ToUnixTimeMilliseconds());
            insert.Parameters.AddWithValue(
                "$expires_at",
                (object?)expiresAt?.ToUnixTimeMilliseconds() ?? DBNull.Value);
            await insert.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }

        await using (var blob = connection.CreateCommand())
        {
            blob.Transaction = transaction;
            blob.CommandText = """
                INSERT INTO media_blobs (
                    content_hash, mime_type, encoded_bytes, pixel_width, pixel_height, state, created_at)
                VALUES ($hash, $mime, $bytes, $width, $height, 'ready', $created_at)
                ON CONFLICT(content_hash) DO UPDATE SET state = 'ready';
                """;
            blob.Parameters.AddWithValue("$hash", image.ContentHash);
            blob.Parameters.AddWithValue("$mime", image.MimeType);
            blob.Parameters.AddWithValue("$bytes", image.EncodedBytes);
            blob.Parameters.AddWithValue("$width", image.PixelWidth);
            blob.Parameters.AddWithValue("$height", image.PixelHeight);
            blob.Parameters.AddWithValue("$created_at", createdAt.ToUnixTimeMilliseconds());
            await blob.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }

        await using var mediaRef = connection.CreateCommand();
        mediaRef.Transaction = transaction;
        mediaRef.CommandText = """
            INSERT INTO clip_media (event_id, content_hash, state)
            VALUES ($event_id, $hash, 'ready');
            """;
        mediaRef.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
        mediaRef.Parameters.AddWithValue("$hash", image.ContentHash);
        await mediaRef.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async ValueTask DetachClipMediaAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string eventId,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = "DELETE FROM clip_media WHERE event_id = $event_id;";
        command.Parameters.AddWithValue("$event_id", eventId);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async ValueTask DetachOrphanedClipMediaAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        await ExecuteNonQueryAsync(
            connection,
            transaction,
            """
            DELETE FROM clip_media
            WHERE event_id NOT IN (SELECT event_id FROM clips)
               OR event_id IN (
                SELECT event_id FROM clips WHERE deleted_at IS NOT NULL OR content_hash = '');
            """,
            cancellationToken).ConfigureAwait(false);
    }

    private async ValueTask CollectUnreferencedBlobsAsync(CancellationToken cancellationToken)
    {
        await mediaLifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT content_hash FROM media_blobs;";
        var live = new List<string>();
        await using (var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false))
        {
            while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            {
                live.Add(reader.GetString(0));
            }
        }

        await using var referenced = connection.CreateCommand();
        referenced.CommandText = "SELECT DISTINCT content_hash FROM clip_media;";
        var keep = new HashSet<string>(StringComparer.Ordinal);
        await using (var reader = await referenced.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false))
        {
            while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            {
                keep.Add(reader.GetString(0));
            }
        }

        var stale = live.Where(hash => !keep.Contains(hash)).ToArray();
        if (stale.Length == 0)
        {
            media.DeleteUnreferenced(keep, gracePeriod: BlobGcGrace);
            return;
        }

        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var hash in stale)
            {
                await using var delete = connection.CreateCommand();
                delete.Transaction = transaction;
                delete.CommandText = "DELETE FROM media_blobs WHERE content_hash = $hash;";
                delete.Parameters.AddWithValue("$hash", hash);
                await delete.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }

        foreach (var hash in stale)
        {
            media.DeleteBlob(hash);
        }

        media.DeleteUnreferenced(keep, gracePeriod: BlobGcGrace);
        }
        finally
        {
            mediaLifecycle.Release();
        }
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

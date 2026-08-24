using System.Data;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;
using ClipSync.Core.Sync;
using Microsoft.Data.Sqlite;

namespace ClipSync.Core.Storage;

public sealed record OriginSequenceRanges(string OriginDeviceId, IReadOnlyList<SequenceRange> Ranges);

public sealed partial class SqliteClipboardEventStore
{
    private const string SyncableColumns = """
        c.event_id,
        c.origin_device_id,
        c.origin_seq,
        c.content,
        c.content_hash,
        c.source_app,
        c.created_at,
        c.expires_at,
        c.terminal_reason,
        c.kind,
        b.mime_type,
        b.encoded_bytes,
        b.pixel_width,
        b.pixel_height
        """;

    public string LocalDeviceId => localDeviceId;

    public async ValueTask<PairedDevice> UpsertDeviceAsync(
        NewPairedDevice device,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(device);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO devices (
                device_id, display_name, platform, certificate_fingerprint,
                pair_secret_protected, trust_epoch, created_at)
            VALUES ($device_id, $display_name, $platform, $fingerprint, $secret, 1, $now)
            ON CONFLICT(device_id) DO UPDATE SET
                display_name = excluded.display_name,
                platform = excluded.platform,
                certificate_fingerprint = excluded.certificate_fingerprint,
                pair_secret_protected = excluded.pair_secret_protected,
                trust_epoch = devices.trust_epoch + 1,
                revoked_at = NULL;
            """;
        command.Parameters.AddWithValue("$device_id", device.DeviceId);
        command.Parameters.AddWithValue("$display_name", device.DisplayName);
        command.Parameters.AddWithValue("$platform", device.Platform);
        command.Parameters.AddWithValue("$fingerprint", device.CertificateFingerprint);
        command.Parameters.AddWithValue("$secret", device.PairSecretProtected);
        command.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);

        return (await GetDeviceAsync(device.DeviceId, cancellationToken).ConfigureAwait(false))!;
    }

    /// <summary>
    /// Stores a pairing at an explicitly agreed trust epoch. The scanner side uses this with
    /// the epoch from the confirm response so both stores agree after any re-pairing.
    /// </summary>
    public async ValueTask<PairedDevice> UpsertDeviceWithEpochAsync(
        NewPairedDevice device,
        long trustEpoch,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(device);
        ArgumentOutOfRangeException.ThrowIfLessThan(trustEpoch, 1);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO devices (
                device_id, display_name, platform, certificate_fingerprint,
                pair_secret_protected, trust_epoch, created_at)
            VALUES ($device_id, $display_name, $platform, $fingerprint, $secret, $epoch, $now)
            ON CONFLICT(device_id) DO UPDATE SET
                display_name = excluded.display_name,
                platform = excluded.platform,
                certificate_fingerprint = excluded.certificate_fingerprint,
                pair_secret_protected = excluded.pair_secret_protected,
                trust_epoch = excluded.trust_epoch,
                revoked_at = NULL;
            """;
        command.Parameters.AddWithValue("$device_id", device.DeviceId);
        command.Parameters.AddWithValue("$display_name", device.DisplayName);
        command.Parameters.AddWithValue("$platform", device.Platform);
        command.Parameters.AddWithValue("$fingerprint", device.CertificateFingerprint);
        command.Parameters.AddWithValue("$secret", device.PairSecretProtected);
        command.Parameters.AddWithValue("$epoch", trustEpoch);
        command.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);

        return (await GetDeviceAsync(device.DeviceId, cancellationToken).ConfigureAwait(false))!;
    }

    public async ValueTask<PairedDevice?> GetDeviceAsync(
        string deviceId,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var devices = await ReadDevicesAsync(connection, deviceId, cancellationToken).ConfigureAwait(false);
        return devices.Count == 0 ? null : devices[0];
    }

    public async ValueTask<IReadOnlyList<PairedDevice>> ListDevicesAsync(CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        return await ReadDevicesAsync(connection, deviceId: null, cancellationToken).ConfigureAwait(false);
    }

    public async ValueTask<bool> RevokeDeviceAsync(
        string deviceId,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            await using var revoke = connection.CreateCommand();
            revoke.Transaction = transaction;
            revoke.CommandText = """
                UPDATE devices
                SET revoked_at = $now,
                    pair_secret_protected = '',
                    trust_epoch = trust_epoch + 1
                WHERE device_id = $device_id AND revoked_at IS NULL;
                """;
            revoke.Parameters.AddWithValue("$device_id", deviceId);
            revoke.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            var revoked = await revoke.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) == 1;

            if (revoked)
            {
                await using var clearOutbox = connection.CreateCommand();
                clearOutbox.Transaction = transaction;
                clearOutbox.CommandText = "DELETE FROM outbox WHERE peer_id = $peer_id;";
                clearOutbox.Parameters.AddWithValue("$peer_id", deviceId);
                await clearOutbox.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            return revoked;
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    public async ValueTask UpdateDeviceLastSeenAsync(
        string deviceId,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "UPDATE devices SET last_seen_at = $now WHERE device_id = $device_id;";
        command.Parameters.AddWithValue("$device_id", deviceId);
        command.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    public async ValueTask<bool> RenameDeviceAsync(
        string deviceId,
        string displayName,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        ArgumentException.ThrowIfNullOrWhiteSpace(displayName);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "UPDATE devices SET display_name = $display_name WHERE device_id = $device_id;";
        command.Parameters.AddWithValue("$device_id", deviceId);
        command.Parameters.AddWithValue("$display_name", displayName.Trim());
        return await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) == 1;
    }

    /// <summary>Every origin's persisted receive state, including this device's own contiguous history.</summary>
    public async ValueTask<IReadOnlyDictionary<string, OriginReceiveState>> GetKnownVectorAsync(
        CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT origin_device_id, contiguous_seq, received_ranges FROM origin_receive_state;";

        var vector = new Dictionary<string, OriginReceiveState>(StringComparer.Ordinal);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            vector[reader.GetString(0)] = new OriginReceiveState(
                reader.GetInt64(1),
                SequenceRangeJson.Deserialize(reader.GetString(2)));
        }

        return vector;
    }

    /// <summary>
    /// Raises the local allocator so the next capture is above <paramref name="highestSeq"/>.
    /// Used when a peer's known_vector shows it already holds this origin further than
    /// the local database (same device id after a local reset).
    /// </summary>
    public async ValueTask AdoptPeerCoverageOfLocalOriginAsync(
        long highestSeq,
        CancellationToken cancellationToken = default)
    {
        if (highestSeq < 1)
        {
            return;
        }

        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO local_sequences (device_id, next_seq)
            VALUES ($device_id, $next)
            ON CONFLICT(device_id) DO UPDATE SET
                next_seq = MAX(local_sequences.next_seq, excluded.next_seq);
            """;
        command.Parameters.AddWithValue("$device_id", localDeviceId);
        command.Parameters.AddWithValue("$next", highestSeq + 1);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    public async ValueTask<RemoteStoreResult> StoreRemoteEventAsync(
        RemoteClipEvent remoteEvent,
        string? sourcePeerId,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(remoteEvent);
        if (string.Equals(remoteEvent.OriginDeviceId, localDeviceId, StringComparison.Ordinal))
        {
            throw new ArgumentException("Remote events cannot claim this device as origin.", nameof(remoteEvent));
        }

        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        if (remoteEvent.IsImage)
        {
            await mediaLifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        }

        try
        {
        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            var conflict = await CheckRemoteIdentityAsync(
                connection,
                transaction,
                remoteEvent.EventId,
                remoteEvent.OriginDeviceId,
                remoteEvent.OriginSeq,
                remoteEvent.ContentHash,
                cancellationToken).ConfigureAwait(false);
            if (conflict is not null)
            {
                if (conflict is RemoteStoreResult.AlreadyPersisted)
                {
                    await AdvanceRemoteReceiveStateAsync(
                        connection,
                        transaction,
                        remoteEvent.OriginDeviceId,
                        remoteEvent.OriginSeq,
                        cancellationToken).ConfigureAwait(false);
                    await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
                    return conflict;
                }

                await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
                return conflict;
            }

            if (remoteEvent.IsImage)
            {
                if (!media.Exists(remoteEvent.ContentHash))
                {
                    throw new InvalidOperationException("MEDIA_STORAGE_FAILED");
                }

                var inspect = ImageCodec.TryInspectFile(
                    media.RequirePath(remoteEvent.ContentHash),
                    out var validated,
                    remoteEvent.ContentHash);
                if (inspect != ImageCodecError.Ok || validated is null)
                {
                    throw new InvalidOperationException("MEDIA_DECODE_FAILED");
                }

                await InsertImageClipAsync(
                    connection,
                    transaction,
                    remoteEvent.EventId,
                    remoteEvent.OriginDeviceId,
                    remoteEvent.OriginSeq,
                    validated,
                    remoteEvent.SourceApp,
                    remoteEvent.CreatedAt,
                    remoteEvent.ExpiresAt,
                    cancellationToken).ConfigureAwait(false);
            }
            else
            {
                await using var insert = connection.CreateCommand();
                insert.Transaction = transaction;
                insert.CommandText = """
                    INSERT INTO clips (
                        event_id, origin_device_id, origin_seq, kind, content, content_hash,
                        source_app, created_at, expires_at, deleted_at, terminal_reason)
                    VALUES ($event_id, $origin, $seq, 'text', $content, $hash, $source_app, $created_at, $expires_at, NULL, NULL);
                    """;
                insert.Parameters.AddWithValue("$event_id", remoteEvent.EventId.ToString("D"));
                insert.Parameters.AddWithValue("$origin", remoteEvent.OriginDeviceId);
                insert.Parameters.AddWithValue("$seq", remoteEvent.OriginSeq);
                insert.Parameters.AddWithValue("$content", remoteEvent.Content ?? string.Empty);
                insert.Parameters.AddWithValue("$hash", remoteEvent.ContentHash);
                insert.Parameters.AddWithValue("$source_app", (object?)remoteEvent.SourceApp ?? DBNull.Value);
                insert.Parameters.AddWithValue("$created_at", remoteEvent.CreatedAt.ToUnixTimeMilliseconds());
                insert.Parameters.AddWithValue(
                    "$expires_at",
                    (object?)remoteEvent.ExpiresAt?.ToUnixTimeMilliseconds() ?? DBNull.Value);
                await insert.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            var state = await AdvanceRemoteReceiveStateAsync(
                connection,
                transaction,
                remoteEvent.OriginDeviceId,
                remoteEvent.OriginSeq,
                cancellationToken).ConfigureAwait(false);

            await EnqueueOutboxFanOutAsync(
                connection,
                transaction,
                remoteEvent.EventId,
                remoteEvent.OriginDeviceId,
                remoteEvent.OriginSeq,
                sourcePeerId,
                cancellationToken).ConfigureAwait(false);

            await InjectFaultAsync(StorageFaultPoint.BeforeCommit, cancellationToken).ConfigureAwait(false);
            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            return new RemoteStoreResult.Stored(state);
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
        }
        finally
        {
            if (remoteEvent.IsImage)
            {
                mediaLifecycle.Release();
            }
        }
    }

    public async ValueTask<RemoteStoreResult> StoreRemoteTerminalAsync(
        RemoteTerminalMarker marker,
        string? sourcePeerId,
        DateTimeOffset receivedAt,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(marker);
        if (string.Equals(marker.OriginDeviceId, localDeviceId, StringComparison.Ordinal))
        {
            throw new ArgumentException("Terminal markers cannot claim this device as origin.", nameof(marker));
        }

        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            var conflict = await CheckRemoteIdentityAsync(
                connection,
                transaction,
                marker.EventId,
                marker.OriginDeviceId,
                marker.OriginSeq,
                expectedContentHash: null,
                cancellationToken).ConfigureAwait(false);
            if (conflict is not null)
            {
                if (conflict is RemoteStoreResult.AlreadyPersisted)
                {
                    var upgraded = false;
                    if (string.Equals(marker.Reason, ClipUnavailableReasons.Deleted, StringComparison.Ordinal))
                    {
                        await using var tombstone = CreateSoftDeleteCommand(connection, transaction, receivedAt);
                        tombstone.CommandText += " AND event_id = $event_id;";
                        tombstone.Parameters.AddWithValue("$event_id", marker.EventId.ToString("D"));
                        upgraded = await tombstone.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) == 1;
                        if (upgraded)
                        {
                            await DetachClipMediaAsync(
                                    connection,
                                    transaction,
                                    marker.EventId.ToString("D"),
                                    cancellationToken)
                                .ConfigureAwait(false);
                            await EnqueueOutboxFanOutAsync(
                                    connection,
                                    transaction,
                                    marker.EventId,
                                    marker.OriginDeviceId,
                                    marker.OriginSeq,
                                    sourcePeerId,
                                    cancellationToken)
                                .ConfigureAwait(false);
                        }
                    }

                    var existingState = await AdvanceRemoteReceiveStateAsync(
                        connection,
                        transaction,
                        marker.OriginDeviceId,
                        marker.OriginSeq,
                        cancellationToken).ConfigureAwait(false);
                    await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
                    if (upgraded)
                    {
                        await CollectUnreferencedBlobsAsync(cancellationToken).ConfigureAwait(false);
                        return new RemoteStoreResult.Stored(existingState);
                    }

                    return conflict;
                }

                await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
                return conflict;
            }

            await using (var insert = connection.CreateCommand())
            {
                insert.Transaction = transaction;
                insert.CommandText = """
                    INSERT INTO clips (
                        event_id, origin_device_id, origin_seq, kind, content, content_hash,
                        source_app, created_at, expires_at, deleted_at, terminal_reason)
                    VALUES ($event_id, $origin, $seq, 'text', '', '', NULL, $now, NULL, $now, $reason);
                    """;
                insert.Parameters.AddWithValue("$event_id", marker.EventId.ToString("D"));
                insert.Parameters.AddWithValue("$origin", marker.OriginDeviceId);
                insert.Parameters.AddWithValue("$seq", marker.OriginSeq);
                insert.Parameters.AddWithValue("$now", receivedAt.ToUnixTimeMilliseconds());
                insert.Parameters.AddWithValue("$reason", marker.Reason);
                await insert.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            var state = await AdvanceRemoteReceiveStateAsync(
                connection,
                transaction,
                marker.OriginDeviceId,
                marker.OriginSeq,
                cancellationToken).ConfigureAwait(false);

            await EnqueueOutboxFanOutAsync(
                connection,
                transaction,
                marker.EventId,
                marker.OriginDeviceId,
                marker.OriginSeq,
                sourcePeerId,
                cancellationToken).ConfigureAwait(false);

            await InjectFaultAsync(StorageFaultPoint.BeforeCommit, cancellationToken).ConfigureAwait(false);
            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            return new RemoteStoreResult.Stored(state);
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    /// <summary>Rows for the requested ranges of one origin, capped to <paramref name="maximumEvents"/>, ordered by sequence.</summary>
    public async ValueTask<IReadOnlyList<SyncableClipEvent>> GetSyncableEventsAsync(
        string originDeviceId,
        IReadOnlyList<SequenceRange> ranges,
        int maximumEvents,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(originDeviceId);
        ArgumentNullException.ThrowIfNull(ranges);
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumEvents, 1);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var events = new List<SyncableClipEvent>();
        foreach (var range in ranges)
        {
            if (events.Count >= maximumEvents)
            {
                break;
            }

            await using var command = connection.CreateCommand();
            command.CommandText = $"""
                SELECT {SyncableColumns}
                FROM clips c
                LEFT JOIN clip_media m ON m.event_id = c.event_id
                LEFT JOIN media_blobs b ON b.content_hash = m.content_hash
                WHERE c.origin_device_id = $origin AND c.origin_seq >= $start AND c.origin_seq <= $end
                ORDER BY c.origin_seq
                LIMIT $limit;
                """;
            command.Parameters.AddWithValue("$origin", originDeviceId);
            command.Parameters.AddWithValue("$start", range.StartSeq);
            command.Parameters.AddWithValue("$end", range.EndSeq);
            command.Parameters.AddWithValue("$limit", maximumEvents - events.Count);
            await ReadSyncableEventsAsync(command, events, cancellationToken).ConfigureAwait(false);
        }

        return events;
    }

    [System.Diagnostics.CodeAnalysis.SuppressMessage(
        "Security",
        "CA2100:Review SQL queries for security vulnerabilities",
        Justification = "IN-list tokens are generated $id{n} names only; event id values are bound with Parameters.AddWithValue. Column list is a const.")]
    public async ValueTask<IReadOnlyList<SyncableClipEvent>> GetSyncableEventsByIdsAsync(
        IReadOnlyList<Guid> eventIds,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(eventIds);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        if (eventIds.Count == 0)
        {
            return Array.Empty<SyncableClipEvent>();
        }

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        var parameterNames = new string[eventIds.Count];
        for (var index = 0; index < eventIds.Count; index++)
        {
            parameterNames[index] = $"$id{index}";
            command.Parameters.AddWithValue(parameterNames[index], eventIds[index].ToString("D"));
        }

        command.CommandText = $"""
            SELECT {SyncableColumns}
            FROM clips c
            LEFT JOIN clip_media m ON m.event_id = c.event_id
            LEFT JOIN media_blobs b ON b.content_hash = m.content_hash
            WHERE c.event_id IN ({string.Join(", ", parameterNames)})
            ORDER BY c.origin_device_id, c.origin_seq;
            """;

        var events = new List<SyncableClipEvent>();
        await ReadSyncableEventsAsync(command, events, cancellationToken).ConfigureAwait(false);
        return events;
    }

    /// <summary>Finds live content with the given hash so an announced event can be materialized without a fetch.</summary>
    public async ValueTask<string?> FindLiveContentByHashAsync(
        string contentHash,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(contentHash);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT content FROM clips
            WHERE content_hash = $hash AND deleted_at IS NULL AND kind = 'text'
            LIMIT 1;
            """;
        command.Parameters.AddWithValue("$hash", contentHash);
        return await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false) as string;
    }

    public async ValueTask<IReadOnlyList<(OutboxEntry Entry, SyncableClipEvent Event)>> GetOutboxBatchAsync(
        string peerId,
        int limit,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        ArgumentOutOfRangeException.ThrowIfLessThan(limit, 1);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT o.id, o.peer_id, o.state, o.attempts,
                c.event_id, c.origin_device_id, c.origin_seq, c.content, c.content_hash,
                c.source_app, c.created_at, c.expires_at, c.terminal_reason,
                c.kind, b.mime_type, b.encoded_bytes, b.pixel_width, b.pixel_height
            FROM outbox o
            JOIN clips c ON c.event_id = o.event_id
            LEFT JOIN clip_media m ON m.event_id = c.event_id
            LEFT JOIN media_blobs b ON b.content_hash = m.content_hash
            WHERE o.peer_id = $peer_id AND o.state = $state
            ORDER BY o.id
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$peer_id", peerId);
        command.Parameters.AddWithValue("$state", OutboxEntryStates.Pending);
        command.Parameters.AddWithValue("$limit", limit);

        var batch = new List<(OutboxEntry, SyncableClipEvent)>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            var clip = ReadSyncableEvent(reader, columnOffset: 4);
            batch.Add((
                new OutboxEntry(
                    reader.GetInt64(0),
                    reader.GetString(1),
                    clip.EventId,
                    clip.OriginDeviceId,
                    clip.OriginSeq,
                    reader.GetString(2),
                    reader.GetInt32(3)),
                clip));
        }

        return batch;
    }

    [System.Diagnostics.CodeAnalysis.SuppressMessage(
        "Security",
        "CA2100:Review SQL queries for security vulnerabilities",
        Justification = "IN-list tokens are generated $id{n} names only; outbox id values are bound with Parameters.AddWithValue. State is a const bound as $state.")]
    public async ValueTask MarkOutboxAnnouncedAsync(
        IReadOnlyList<long> outboxIds,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(outboxIds);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        if (outboxIds.Count == 0)
        {
            return;
        }

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        var parameterNames = new string[outboxIds.Count];
        for (var index = 0; index < outboxIds.Count; index++)
        {
            parameterNames[index] = $"$id{index}";
            command.Parameters.AddWithValue(parameterNames[index], outboxIds[index]);
        }

        command.CommandText = $"""
            UPDATE outbox
            SET state = $state, attempts = attempts + 1
            WHERE id IN ({string.Join(", ", parameterNames)});
            """;
        command.Parameters.AddWithValue("$state", OutboxEntryStates.Announced);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    [System.Diagnostics.CodeAnalysis.SuppressMessage(
        "Security",
        "CA2100:Review SQL queries for security vulnerabilities",
        Justification = "IN-list tokens are generated $id{n} names only; outbox id values are bound with Parameters.AddWithValue.")]
    public async ValueTask DeleteOutboxIdsAsync(
        IReadOnlyList<long> outboxIds,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(outboxIds);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        if (outboxIds.Count == 0)
        {
            return;
        }

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        var parameterNames = new string[outboxIds.Count];
        for (var index = 0; index < outboxIds.Count; index++)
        {
            parameterNames[index] = $"$id{index}";
            command.Parameters.AddWithValue(parameterNames[index], outboxIds[index]);
        }

        command.CommandText = $"""
            DELETE FROM outbox
            WHERE id IN ({string.Join(", ", parameterNames)});
            """;
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Returns announced-but-unacked entries to pending, e.g. at the start of a new session.</summary>
    public async ValueTask ResetOutboxToPendingAsync(
        string peerId,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = $"""
            UPDATE outbox SET state = '{OutboxEntryStates.Pending}'
            WHERE peer_id = $peer_id AND state = '{OutboxEntryStates.Announced}';
            """;
        command.Parameters.AddWithValue("$peer_id", peerId);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Removes outbox rows the peer has already persisted according to its known vector or acks, and advances the peer cursor.</summary>
    public async ValueTask ApplyPeerAckRangesAsync(
        string peerId,
        IReadOnlyList<OriginSequenceRanges> acks,
        DateTimeOffset now,
        bool dropTerminalOutbox,
        CancellationToken cancellationToken = default)
    {
        await ApplyPeerAckRangesCoreAsync(peerId, acks, now, dropTerminalOutbox, cancellationToken)
            .ConfigureAwait(false);
    }

    public async ValueTask ApplyPeerAckRangesAsync(
        string peerId,
        IReadOnlyList<OriginSequenceRanges> acks,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        await ApplyPeerAckRangesCoreAsync(peerId, acks, now, dropTerminalOutbox: true, cancellationToken)
            .ConfigureAwait(false);
    }

    private async ValueTask ApplyPeerAckRangesCoreAsync(
        string peerId,
        IReadOnlyList<OriginSequenceRanges> acks,
        DateTimeOffset now,
        bool dropTerminalOutbox,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        ArgumentNullException.ThrowIfNull(acks);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        if (acks.Count == 0)
        {
            return;
        }

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var ack in acks)
            {
                var cursor = await ReadPeerCursorAsync(connection, transaction, peerId, ack.OriginDeviceId, cancellationToken)
                    .ConfigureAwait(false);
                foreach (var range in ack.Ranges)
                {
                    cursor = cursor.AcceptRange(range);
                }

                await WritePeerCursorAsync(connection, transaction, peerId, ack.OriginDeviceId, cursor, now, cancellationToken)
                    .ConfigureAwait(false);

                foreach (var range in ack.Ranges)
                {
                    await using var remove = connection.CreateCommand();
                    remove.Transaction = transaction;
                    if (dropTerminalOutbox)
                    {
                        remove.CommandText = """
                            DELETE FROM outbox
                            WHERE peer_id = $peer_id AND origin_device_id = $origin
                              AND origin_seq >= $start AND origin_seq <= $end;
                            """;
                    }
                    else
                    {
                        remove.CommandText = """
                            DELETE FROM outbox
                            WHERE peer_id = $peer_id AND origin_device_id = $origin
                              AND origin_seq >= $start AND origin_seq <= $end
                              AND event_id IN (
                                SELECT event_id FROM clips
                                WHERE deleted_at IS NULL AND terminal_reason IS NULL
                              );
                            """;
                    }
                    remove.Parameters.AddWithValue("$peer_id", peerId);
                    remove.Parameters.AddWithValue("$origin", ack.OriginDeviceId);
                    remove.Parameters.AddWithValue("$start", range.StartSeq);
                    remove.Parameters.AddWithValue("$end", range.EndSeq);
                    await remove.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
                }
            }

            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    public async ValueTask<IReadOnlyDictionary<string, OriginReceiveState>> GetPeerCursorsAsync(
        string peerId,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT origin_device_id, contiguous_seq, received_ranges
            FROM peer_cursors
            WHERE peer_id = $peer_id;
            """;
        command.Parameters.AddWithValue("$peer_id", peerId);

        var cursors = new Dictionary<string, OriginReceiveState>(StringComparer.Ordinal);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            cursors[reader.GetString(0)] = new OriginReceiveState(
                reader.GetInt64(1),
                SequenceRangeJson.Deserialize(reader.GetString(2)));
        }

        return cursors;
    }

    private static async ValueTask<RemoteStoreResult?> CheckRemoteIdentityAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        Guid eventId,
        string originDeviceId,
        long originSeq,
        string? expectedContentHash,
        CancellationToken cancellationToken)
    {
        await using (var byKey = connection.CreateCommand())
        {
            byKey.Transaction = transaction;
            byKey.CommandText = """
                SELECT event_id, content_hash, deleted_at
                FROM clips
                WHERE origin_device_id = $origin AND origin_seq = $seq
                LIMIT 1;
                """;
            byKey.Parameters.AddWithValue("$origin", originDeviceId);
            byKey.Parameters.AddWithValue("$seq", originSeq);
            await using var reader = await byKey.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
            if (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            {
                var existingEventId = reader.GetString(0);
                var existingHash = reader.GetString(1);
                var isTerminal = !reader.IsDBNull(2);
                if (!string.Equals(existingEventId, eventId.ToString("D"), StringComparison.Ordinal))
                {
                    return new RemoteStoreResult.IdentityConflict("origin sequence maps to a different event id");
                }

                if (!isTerminal
                    && expectedContentHash is not null
                    && !string.Equals(existingHash, expectedContentHash, StringComparison.Ordinal))
                {
                    return new RemoteStoreResult.IdentityConflict("origin sequence maps to different content");
                }

                return new RemoteStoreResult.AlreadyPersisted();
            }
        }

        await using var byEventId = connection.CreateCommand();
        byEventId.Transaction = transaction;
        byEventId.CommandText = "SELECT 1 FROM clips WHERE event_id = $event_id LIMIT 1;";
        byEventId.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
        var existing = await byEventId.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false);
        return existing is null
            ? null
            : new RemoteStoreResult.IdentityConflict("event id maps to a different origin sequence");
    }

    private static async ValueTask<OriginReceiveState> AdvanceRemoteReceiveStateAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string originDeviceId,
        long originSeq,
        CancellationToken cancellationToken)
    {
        OriginReceiveState state;
        await using (var read = connection.CreateCommand())
        {
            read.Transaction = transaction;
            read.CommandText = """
                SELECT contiguous_seq, received_ranges FROM origin_receive_state
                WHERE origin_device_id = $origin;
                """;
            read.Parameters.AddWithValue("$origin", originDeviceId);
            await using var reader = await read.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
            state = await reader.ReadAsync(cancellationToken).ConfigureAwait(false)
                ? new OriginReceiveState(reader.GetInt64(0), SequenceRangeJson.Deserialize(reader.GetString(1)))
                : OriginReceiveState.Empty;
        }

        state = state.Accept(originSeq);

        await using var write = connection.CreateCommand();
        write.Transaction = transaction;
        write.CommandText = """
            INSERT INTO origin_receive_state (origin_device_id, contiguous_seq, received_ranges)
            VALUES ($origin, $contiguous, $ranges)
            ON CONFLICT(origin_device_id) DO UPDATE SET
                contiguous_seq = excluded.contiguous_seq,
                received_ranges = excluded.received_ranges;
            """;
        write.Parameters.AddWithValue("$origin", originDeviceId);
        write.Parameters.AddWithValue("$contiguous", state.ContiguousSeq);
        write.Parameters.AddWithValue("$ranges", SequenceRangeJson.Serialize(state.ReceivedRanges));
        await write.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        return state;
    }

    private static async ValueTask EnqueueOutboxFanOutAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        Guid eventId,
        string originDeviceId,
        long originSeq,
        string? excludedPeerId,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO outbox (peer_id, event_id, origin_device_id, origin_seq)
            SELECT device_id, $event_id, $origin, $seq
            FROM devices
            WHERE revoked_at IS NULL
              AND device_id <> $origin
              AND ($excluded_peer IS NULL OR device_id <> $excluded_peer)
            ON CONFLICT(peer_id, origin_device_id, origin_seq) DO UPDATE SET
                event_id = excluded.event_id,
                state = 'pending',
                attempts = 0,
                next_attempt_at = 0,
                last_error_code = NULL;
            """;
        command.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
        command.Parameters.AddWithValue("$origin", originDeviceId);
        command.Parameters.AddWithValue("$seq", originSeq);
        command.Parameters.AddWithValue("$excluded_peer", (object?)excludedPeerId ?? DBNull.Value);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async ValueTask<OriginReceiveState> ReadPeerCursorAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string peerId,
        string originDeviceId,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT contiguous_seq, received_ranges FROM peer_cursors
            WHERE peer_id = $peer_id AND origin_device_id = $origin;
            """;
        command.Parameters.AddWithValue("$peer_id", peerId);
        command.Parameters.AddWithValue("$origin", originDeviceId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        return await reader.ReadAsync(cancellationToken).ConfigureAwait(false)
            ? new OriginReceiveState(reader.GetInt64(0), SequenceRangeJson.Deserialize(reader.GetString(1)))
            : OriginReceiveState.Empty;
    }

    private static async ValueTask WritePeerCursorAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string peerId,
        string originDeviceId,
        OriginReceiveState cursor,
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO peer_cursors (peer_id, origin_device_id, contiguous_seq, received_ranges, updated_at)
            VALUES ($peer_id, $origin, $contiguous, $ranges, $now)
            ON CONFLICT(peer_id, origin_device_id) DO UPDATE SET
                contiguous_seq = excluded.contiguous_seq,
                received_ranges = excluded.received_ranges,
                updated_at = excluded.updated_at;
            """;
        command.Parameters.AddWithValue("$peer_id", peerId);
        command.Parameters.AddWithValue("$origin", originDeviceId);
        command.Parameters.AddWithValue("$contiguous", cursor.ContiguousSeq);
        command.Parameters.AddWithValue("$ranges", SequenceRangeJson.Serialize(cursor.ReceivedRanges));
        command.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async ValueTask<IReadOnlyList<PairedDevice>> ReadDevicesAsync(
        SqliteConnection connection,
        string? deviceId,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT device_id, display_name, platform, certificate_fingerprint,
                   pair_secret_protected, trust_epoch, created_at, last_seen_at, revoked_at
            FROM devices
            WHERE $device_id IS NULL OR device_id = $device_id
            ORDER BY created_at, device_id;
            """;
        command.Parameters.AddWithValue("$device_id", (object?)deviceId ?? DBNull.Value);

        var devices = new List<PairedDevice>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            devices.Add(new PairedDevice(
                reader.GetString(0),
                reader.GetString(1),
                reader.GetString(2),
                reader.GetString(3),
                reader.GetString(4),
                reader.GetInt64(5),
                DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(6)),
                reader.IsDBNull(7) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(7)),
                reader.IsDBNull(8) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(8))));
        }

        return devices;
    }

    private static async ValueTask ReadSyncableEventsAsync(
        SqliteCommand command,
        List<SyncableClipEvent> events,
        CancellationToken cancellationToken)
    {
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            events.Add(ReadSyncableEvent(reader, columnOffset: 0));
        }
    }

    private static SyncableClipEvent ReadSyncableEvent(SqliteDataReader reader, int columnOffset)
    {
        var terminalReason = reader.IsDBNull(columnOffset + 8) ? null : reader.GetString(columnOffset + 8);
        var kind = reader.FieldCount > columnOffset + 9 && !reader.IsDBNull(columnOffset + 9)
            ? reader.GetString(columnOffset + 9)
            : "text";
        var content = terminalReason is null && !reader.IsDBNull(columnOffset + 3)
            ? reader.GetString(columnOffset + 3)
            : null;
        return new SyncableClipEvent(
            Guid.Parse(reader.GetString(columnOffset)),
            reader.GetString(columnOffset + 1),
            reader.GetInt64(columnOffset + 2),
            content,
            terminalReason is null ? reader.GetString(columnOffset + 4) : null,
            reader.IsDBNull(columnOffset + 5) ? null : reader.GetString(columnOffset + 5),
            DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(columnOffset + 6)),
            reader.IsDBNull(columnOffset + 7) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(columnOffset + 7)),
            terminalReason,
            kind,
            reader.FieldCount > columnOffset + 10 && !reader.IsDBNull(columnOffset + 10)
                ? reader.GetString(columnOffset + 10)
                : null,
            reader.FieldCount > columnOffset + 11 && !reader.IsDBNull(columnOffset + 11)
                ? reader.GetInt32(columnOffset + 11)
                : null,
            reader.FieldCount > columnOffset + 12 && !reader.IsDBNull(columnOffset + 12)
                ? reader.GetInt32(columnOffset + 12)
                : null,
            reader.FieldCount > columnOffset + 13 && !reader.IsDBNull(columnOffset + 13)
                ? reader.GetInt32(columnOffset + 13)
                : null);
    }

    public async ValueTask<bool> FindLiveBlobByHashAsync(
        string contentHash,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(contentHash);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        if (!media.Exists(contentHash))
        {
            return false;
        }

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT 1 FROM clip_media m
            JOIN clips c ON c.event_id = m.event_id
            WHERE m.content_hash = $hash AND c.deleted_at IS NULL AND m.state = 'ready'
            LIMIT 1;
            """;
        command.Parameters.AddWithValue("$hash", contentHash);
        return await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false) is not null;
    }

    public async ValueTask StoreLocalUnsupportedMediaAsync(
        Guid eventId,
        string originDeviceId,
        long originSeq,
        DateTimeOffset at,
        CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE clips
            SET terminal_reason = 'unsupported_media'
            WHERE event_id = $event_id AND origin_device_id = $origin AND origin_seq = $seq;
            """;
        command.Parameters.AddWithValue("$event_id", eventId.ToString("D"));
        command.Parameters.AddWithValue("$origin", originDeviceId);
        command.Parameters.AddWithValue("$seq", originSeq);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        _ = at;
    }
}

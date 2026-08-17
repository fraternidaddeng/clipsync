package com.clipsync.android.storage

/**
 * JVM test factory. [ClipRepository]'s persistence constructor is `internal` to this
 * module; this helper keeps history/share/tile tests from depending on Room.
 */
fun createTestClipRepository(
    localDeviceId: String = TEST_LOCAL_DEVICE_ID,
): ClipRepository = ClipRepository(InMemoryClipPersistence(), localDeviceId)

const val TEST_LOCAL_DEVICE_ID = "11111111-1111-4111-8111-111111111111"
const val TEST_PEER_DEVICE_ID = "22222222-2222-4222-8222-222222222222"

package com.clipsync.android.pairing

/**
 * Where a confirm attempt stands, so the waiting screen can tell "still dialing" apart
 * from "the PC has the request and a person must approve it".
 */
enum class PairingPhase {
    /** Dialing the QR hosts in order; nothing has reached the PC yet. */
    CONNECTING,

    /** TCP/TLS is up with one host and the confirm request is on the wire. */
    AWAITING_APPROVAL,
}

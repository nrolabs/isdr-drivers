# iSDR Drivers (`isdr-drivers`) — Technical Specification

High-level contracts and invariants. Implementation history belongs in the git
log, not here.

## 1. Scope and boundary

`isdr-drivers` is a standalone GPL Android app: a launcher activity
(`DriverActivity`, which also handles `USB_DEVICE_ATTACHED`) plus an exported
foreground service (`DriverService`, `connectedDevice`) that serves the
`isdr-proto` wire contract over a socket. One accepted connection becomes one
`DriverSession`.

Its job is hardware access and `isdr-proto` serialization. **No demodulation
chain lives here** — no channelizer, decimator or demodulator; that is
`isdr-app`/`isdr-station` work. What does run locally is the minimum the wire
contract needs: the spectrum path (`FFTProcessor`, `SpectrumWorker`) and the TX
interpolators that lift 48 kHz modulator output to the radio's sample rate.
Drivers must not depend on app resources or classes.

## 2. Session ownership (invariant)

Per-generation connection state — socket, threads, sequence trackers,
accumulators — belongs in a single session object published atomically, and
teardown operates **only on the session instance it captured**. `Hl2Client`
is the reference implementation: a private `Session` class behind an
`AtomicReference`, with `disconnect` comparing identity before clearing
(`sessionRef.compareAndSet(s, null)`), so a late teardown can never dismantle a
newer connection.

This is the target pattern for every client. `G2Client`, `HackRfClient`,
`RTLUSBClient` and `RTLTCPClient` still keep flat `@Volatile` fields; converting
them is open work, not an alternative design.

## 3. Thread failure is never silent (invariant)

Radio loops are raw daemon threads created by the `DspThread` factory at
`THREAD_PRIORITY_URGENT_AUDIO` (delivery threads at `THREAD_PRIORITY_AUDIO`).
The factory wraps each body in a catch-all that logs the throwable **with its
stack** and invokes an `onFailure` callback, which clients wire to a real link
teardown (`failLink()` / `failStream()`).

The invariant: a dead thread must never leave the session reporting
"connected". No loop may swallow its exception and exit quietly. Inside these
loops, allocation is a bug — every per-block allocation is a GC pause and an
underrun.

## 4. Payload limits

A session accepts at most **4 KiB** per frame before authentication and
**1 MiB** after it. The largest legitimate inbound frame is `CMD_TX_IQ` at
~8 KiB, so the cap is pure abuse protection, not a working limit.

## 5. Per-radio notes

* **RTL-SDR** — tuner I²C and register access are serialized on a single pinned
  worker (`rtlsdr-usb` executor); commands are FIFO-queued through
  `sendCommand`. Bulk IQ transfer runs on its own threads, so control transfers
  proceed without interrupting the stream. `RTLUSBClient` instances are
  single-use: after `disconnect()` the executor is shut down, so reconnecting
  means a new instance.
* **Hermes-Lite 2** — Ethernet/UDP. The wire format is the Android-free
  `Hl2Protocol` codec (fixed offsets and C0 register banks), JVM-tested.
  `Hl2Client` owns transport, RX accumulation and TX streaming.
  **IO board:** the control register is reset exactly once per session, and
  teardown writes `REG_RF_INPUTS = 0` while the socket is still open, flushing
  control frames until it lands — the J9 routing pins are sticky in firmware and
  stay latched otherwise.
* **ANAN-G2** — the stock Protocol-2 port map: separate UDP ports for general
  control (1024), high priority / run + PTT (1027), TX IQ (1029) and the DDC RX
  streams, which are keyed on the radio's **source** port (1035..1041). PTT
  rides the high-priority port; TX IQ is wall-clock paced on its own thread, so
  keying never queues behind sample data.

## 6. Sequence tracking

`SeqTracker` (in `core/`) watches the 32-bit packet sequence numbers on HL2 EP6
and each G2 receiver. A discontinuity is counted as a gap event and surfaces to
the host as `RadioTelemetry.rxGaps` in `EV_TELEMETRY`.


## Native CI-V scope and USB contract

* Supported Icom scope streams preserve the physical sweep's lower/upper edges,
  including signed lower edges. CENTER carries centre plus half-span; FIX,
  SCROLL-C and SCROLL-F carry edges. Full displayed span ladders are 5 kHz–1 MHz,
  with R8600 extensions through 5 MHz and IC-905 extensions through 50 MHz.
* IC-7610 requires 689 bins (0–200); the other scope profiles require 475
  (0–160). USB divisions are header plus 50-bin chunks and a final 39/25 bins;
  LAN may deliver one whole frame. A partial, oversized, malformed or wrongly
  ordered sweep is discarded. IC-905 infers 5/6-byte frequency geometry from
  the header shape, including 6+5 CENTER and 6+6 edge modes at 10 GHz.
* Each physical sweep emits `EV_SCOPE_DATA` with sequence, signed edges and
  U8 spectrum atomically. Identical consecutive sweeps still receive different
  sequences. Out-of-range carries no bins. No CAT `EV_DATA` compatibility copy
  is emitted. Scope sequence persists for the TCP session; retired OPEN
  callbacks cannot deliver into a replacement session. Station transports
  these frames on droppable RX media, leaving control traffic independent.
* CI-V writes report success only after the requested value is read back.
  FIL changes first read the physical DATA submode and preserve D1/D2/D3.
  Widths must lie on the mode's exact grid; RTTY ends at 2700 Hz. Width reads
  and writes confirm the same physical mode/DATA/FIL before and after the
  width register, so a front-panel change cannot validate the wrong table.
  Mode broadcasts without DATA wait for a complete 0x26 read-back before
  reporting the selected-VFO mode. IC-7851
  supports IF width and AGC OFF, but provides no 0x27 scope stream. The original
  IC-7300 has 50 CTCSS tones; MK2-specific 60.0/120.0 Hz are not advertised.
* Native Android polls S-meter while receiving and Po/SWR while transmitting,
  plus frequency/mode/PTT/FIL when panel transceive broadcasts are disabled.
  All exposed receive knobs are read in a 250 ms round-robin cycle; confirmed
  physical changes reach the app through `EV_CAT_CONTROL`. Mode-local FIL,
  PBT and width observations follow their confirmed `EV_CAT_MODE`; changing
  mode invalidates their deduplication so equal values are published again.
  Background polls
  yield to pending operator requests after completing any paired bus read.
  TX ticks publish Po and SWR as one coherent forward/reverse telemetry pair;
  unavailable SWR leaves only forward power valid, without reusing old reverse
  power or implying a perfect match. NR snapshots and restores both its
  function and stored strength if either apply/verification leg fails, verifies
  the rollback, and reports uncertainty explicitly if restoration fails.
  Meter readings are display estimates: S9 uses the HF convention −73 dBm,
  interpolation uses the documented 120/241 anchors, and the upper Po/SWR
  ranges use the same approximate 255→120% and 240→6 extensions as Desktop.
  They are not a substitute for calibrated RF power/return-loss measurements.
* USB serial supports CDC-ACM and verified Silicon Labs CP210x default IDs.
  CP210x uses vendor-interface requests, verifies baud/8N1/modem-line readback,
  and keeps DTR/RTS inactive (radios can assign them to SEND/CW). CDC setup also
  checks every transfer and reads line coding back. On multi-UART CI-V devices,
  the selected interface must answer a read-only 0x19/0x00 query; interface
  order or the Enhanced/Standard label is not treated as CI-V identity.
  All candidate USB devices and their UARTs are tried until CI-V replies;
  a non-radio serial adapter earlier in a hub cannot hide the radio.
  USB scope operation requires the radio's CI-V USB Unlink and 115200 settings;
  this does not change menu settings automatically. USB physical interoperability
  remains hardware QA; JVM tests prove request bytes, failure handling and
  interface proof, not a bench run on every radio.

USB implementation references: [Silicon Labs AN571](https://www.silabs.com/documents/public/application-notes/AN571.pdf)
and [Linux CP210x driver](https://github.com/torvalds/linux/blob/master/drivers/usb/serial/cp210x.c).

## Native Kenwood operating-control contract

* TS-890S and TS-990S use the modern OM/FL/SL dialect. OM reports from Sub
  never change Main mode; DATA2/3 normalize to the common DATA flag while
  operations unrelated to mode retain the physical OM value. Explicit DATA
  selection chooses DATA1. PSK and reverse PSK have distinct mode codes.
* Session startup enables AI2 and explicitly sends RX, requiring its state
  report before operation. TX/RX are setters, not polling queries. Mode writes
  require confirmed RX and TF-SET off; TS reports TF-SET only. On TS-990S,
  OM and RA writes temporarily select CB Main and verify restoration of the
  original control band, because their receiver digits do not select the
  write target. Read waiters include receiver/subcommand selectors.
* Filter and width operations read physical mode before and after confirmation.
  Width additionally preserves the physical filter selection, accepts only
  an exact entry in the documented firmware table, and rejects SSB/AM/FM
  meanings that depend on radio menu state. NR mode and its stored NR1 effect
  level form one transaction; failed writes restore and verify both. NR2 or
  unavailable NR1 level is reported as unknown (`-1`), never as NR1/default.
* Physical controls are refreshed by AI and a low-rate polling rotation. TX
  SM Po and RM2 SWR are read under one bus reservation and delivered as one
  telemetry frame; a TX/RX transition invalidates the observation. Po uses
  the manuals' quantized Voice meter categories in watts divided by model
  rated power; values above full scale are retained. These categories are not
  a laboratory RF calibration. SWR OVER is represented by reverse=forward;
  an absent SWR produces Po-only flags, without an old reverse observation.
* Scope setup verifies Main BS20 on TS-990S, EXPAND off on TS-890S, and the
  DD0 stream mode. LAN uses DD01; serial uses AI-linked DD04 (TS-890S) or
  DD02 (TS-990S). DD05 is not combined with AI2. TS-990S FV <=1.13 and >=1.20
  select different BS4/SL tables; unknown firmware disables only ambiguous
  tables, leaving other controls and fixed-edge scope available. Current
  firmware includes a 30 kHz BS4 entry. Span changes require CENTER mode and
  exact confirmed readback. EXPAND or Sub selection suspends Main delivery.
* Each serial scope line requires all 32 ordered fragments with one geometry
  generation. Geometry changes discard an incomplete sweep. Self-describing
  DD4 headers bind the complete sweep's edges and report out-of-range with
  empty bins. Scope data remains atomic with its frequency axis; no IQ or
  hardware audio is synthesized from CAT scope samples.

### OPEN snapshot barrier

The writer sends the initial sample rate, frequency, tuner metadata and receiver
context before a successful `EV_OPEN_RESULT`. Success releases the client's
command barrier; a startup snapshot must never become readback for a command
issued after that barrier. The entire startup sequence stays in one non-droppable
writer entry. `OpenEpochGate` still claims the FIFO terminal at COMMITTING and
publishes only after the positive result is written. Station resets its old
channelizer at `CMD_OPEN`, preserving the new snapshot at the successful result.

A command arriving immediately after the peer observes OPEN waits for local
publication to finish, for at most five seconds, before taking geometry or reply
locks. The writer releases this publication barrier on every exit; terminal false,
CLOSE and session shutdown also wake waiters, which recheck the epoch and refuse
revoked sessions. The wait changes neither `OpenEpochGate` decisions nor wire
ordering. Repeater refusals retain their per-opcode terminal FIFO reservation.

# Hardware-in-the-loop checklist (phase 2 comms loss)

Automated coverage is `CommsLossQualityTest` and `SimulatorCommsLossTest`. The first drives a Modbus TCP simulator and an OPC UA simulator through an in-process fault proxy (connection refused, connection reset, stalled bytes, and the upstream process stopped). The second runs against the compose stack: the simulator container is stopped, Toxiproxy disables the listener, and a timeout toxic black-holes the port. Both read quality from the tag engine. The engine clock is advanced by one scan period plus `commsTimeoutMillis` instead of sleeping that long. There is no MQTT driver (M2.6), so MQTT is not in these tests. This list is the pass on real equipment. It is not a substitute for those tests, and it is not signed.

Public quality behavior this list is checked against: a value whose quality is not Good should not be trusted; `Bad_NotConnected` means a connection the value needs is not connected; `Uncertain_LastKnownValue` means the live value is unavailable and the value shown is the last one that was ([quality codes and overlays](-)). Our tag setting chooses between those two codes. The last value and the device source timestamp stay. The gateway timestamp on the quality change is the time the loss was detected. SPEC §5 requires that change within one scan period plus the tag's comms timeout.

## Before you start

- Gateway build under test, already running. Do not restart it during a step.
- One Modbus TCP device and one OPC UA connection, each with at least two tags that already show Good and a real value.
- On each device or connection, one tag left at `commsLoss: badNotConnected` and one set to `uncertainLastKnown`.
- Write down the scan period and `commsTimeoutMillis` for those tags, and the device or session timeout. The pass/fail clock below uses the tag settings, not the socket timeout.
- A second device that stays connected, so a fault on one link does not take the other link's tags bad.
- Someone who can pull a cable, cycle PLC power, and shut a switch port. No live safety system. Nothing in this list is SIL.

## What every step must show

Start the clock when the fault is actually applied (cable out, power off, port down), not when you decide to do it.

| Check | Pass |
|---|---|
| Quality | Within one scan period plus `commsTimeoutMillis`, every tag on the affected device or connection shows that tag's comms-loss code: `Bad_NotConnected` or `Uncertain_LastKnownValue`. |
| Value | The number (or other value) does not change when the quality changes. |
| Source time | The device or server source timestamp stays at the last good sample. |
| Gateway time | The tag timestamp moves to the detection time, and that time is still inside the same deadline. |
| Other devices | Tags on a link you did not touch stay Good. |
| Recovery | When the fault is cleared, every affected tag returns to Good on its own. Do not restart the gateway, and do not delete and recreate the device or connection. |
| Writes | A write attempted while the quality is bad is rejected. After recovery, a write of a test value sticks and reads back. |

If the quality is still Good after the deadline, the step fails even if it goes bad later (for example when a long socket timeout finally expires).

## Steps

1. **Baseline.** Read both tags on each link. Quality Good, values plausible, source timestamps present. Leave them for two scan periods and confirm they stay Good.
2. **Cable pull.** Disconnect the Ethernet cable at the PLC (or at the gateway NIC that reaches only that PLC). Expect the table above. Reconnect the same cable. Expect Good without a gateway restart.
3. **PLC power cycle.** Remove power from the PLC, wait until the tags go bad inside the deadline, then restore power and wait for the PLC to finish booting. Expect Good once the protocol session is back. The gateway process stays up the whole time.
4. **Switch port down.** Administratively disable the switch port facing the PLC (or unplug that port). Same quality, value, and timing as the cable pull. Enable the port again. Expect Good without a gateway restart.
5. **Stalled link, if you can make one.** A switch ACL, a firewall drop, or a tap that black-holes packets without sending a reset. The TCP session stays open and no reset is sent. Quality must still change inside the tag deadline, not only after the device socket timeout. Remove the black hole. Expect Good without a gateway restart.
6. **Second link untouched.** During steps 2–5, read the other device's tags at least once while the first link is bad. They stay Good.
7. **Both codes.** Repeat step 2 once and confirm the `badNotConnected` tag and the `uncertainLastKnown` tag show different codes at the same moment, with the same last value still displayed.

## Sign-off

This section is unsigned. Do not mark it signed until Nick has run the steps on equipment and filled the blank fields.

| Field | Entry |
|---|---|
| Tester | Nick |
| Date | |
| Gateway build (git revision) | |
| Equipment (PLC, switch, cables) | |
| Scan period | |
| Comms timeout | |
| Steps run | |
| Result | unsigned |
| Notes | |
| Signature | |

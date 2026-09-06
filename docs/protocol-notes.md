# Protocol Notes

Target treadmill: LifeSpan TR-1200 DT3.

Known public behavior:

- The console has a Bluetooth sync button on Bluetooth-capable models.
- The console displays time, distance, calories, steps, and speed.
- Some LifeSpan settings mention automatic Bluetooth syncing and multi-user mode.

Unknowns to discover:

- Bluetooth classic vs BLE. Initial testing found the treadmill through Android system pairing, not app BLE scan.
- Advertised device name: `LifeSpan` after system pairing.
- Paired Bluetooth address observed in one local test: `00:0C:BF:29:F3:E6`.
- Service UUIDs.
- Characteristic UUIDs.
- Which characteristics support read, write, notify, or indicate.
- Whether pressing the console Bluetooth button changes advertisement or connection behavior.
- Packet framing and checksum, if any.
- Units and scaling for time, distance, calories, steps, and speed.

Observed so far:

- Android reports the paired `LifeSpan` device as Bluetooth type `2`, which is Classic-only.
- Cached and SDP UUIDs for the paired device were empty in the first capture.
- Next probe should attempt a read-only RFCOMM connection using the standard Serial Port Profile UUID:
  `00001101-0000-1000-8000-00805f9b34fb`.

Capture checklist:

1. Power on the treadmill and console.
2. Start the logger app and scan while idle.
3. Press the console Bluetooth button and scan again.
4. Connect to candidate devices and save service/characteristic inventory.
5. Start a short walk, stop, then press Bluetooth sync.
6. Save raw notification/read logs and record the console values shown at the same time.

Manual comparison fields:

- Date/time of capture.
- Console duration.
- Console distance.
- Console calories.
- Console steps.
- Console speed.
- Units setting.

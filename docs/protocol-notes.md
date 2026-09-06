# Protocol Notes

Target treadmill: LifeSpan TR-1200 DT3.

Known public behavior:

- The console has a Bluetooth sync button on Bluetooth-capable models.
- The console displays time, distance, calories, steps, and speed.
- Some LifeSpan settings mention automatic Bluetooth syncing and multi-user mode.

Unknowns to discover:

- Bluetooth classic vs BLE. The first Android prototype assumes BLE, but this may need to change.
- Advertised device name.
- Service UUIDs.
- Characteristic UUIDs.
- Which characteristics support read, write, notify, or indicate.
- Whether pressing the console Bluetooth button changes advertisement or connection behavior.
- Packet framing and checksum, if any.
- Units and scaling for time, distance, calories, steps, and speed.

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

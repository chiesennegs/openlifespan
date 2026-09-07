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
- Service UUID: `0000fff0-0000-1000-8000-00805f9b34fb`.
- Notify characteristic UUID: `0000fff1-0000-1000-8000-00805f9b34fb`.
- Write characteristic UUID: `0000fff2-0000-1000-8000-00805f9b34fb`.
- Client characteristic config descriptor UUID: `00002902-0000-1000-8000-00805f9b34fb`.
- Which characteristics support read, write, notify, or indicate on the physical TR-1200 DT3.
- Whether pressing the console Bluetooth button changes advertisement or connection behavior.
- Packet framing and checksum, if any.
- Units and scaling for time, distance, calories, steps, and speed.

Observed so far:

- Android reports the paired `LifeSpan` device as Bluetooth type `2`, which is LE-only.
- Cached and SDP UUIDs for the paired device were empty in the first capture.
- A read-only RFCOMM connection using the standard Serial Port Profile UUID failed with "socket might be closed or timeout, ret -1":
  `00001101-0000-1000-8000-00805f9b34fb`.
- Direct RFCOMM channel probing also failed.
- Legacy bytecode uses BLE GATT through Android `connectGatt`, with the LifeSpan service/characteristic UUIDs listed above.
- The console Bluetooth button appears to open a short BLE connection window. A GATT connection requested before pressing the button did not complete until the button was pressed.
- The physical TR-1200 DT3 exposes the expected LifeSpan service and characteristics. `fff1` supports write without response, write, and notify; `fff2` supports write without response and write.
- Android disconnects with status `8` after a short idle period following a command response.
- Android API 33+ returns busy when two GATT writes are attempted back-to-back, so OpenLifeSpan queues commands and waits for `onCharacteristicWrite` before sending the next one.

BLE command clues from legacy bytecode:

- Commands are 5 bytes written to the `fff2` characteristic.
- Notification/response data arrives through the `fff1` characteristic.
- `AA 00 00 00 00` asks for the stored record count.
- `AA FF 00 00 00 00` was observed as the physical treadmill response after querying record count with no captured activity data available or no active sync state.
- `AB 00 00 00 00` begins stored-record retrieval.
- `AB 00 RR RR 00` asks for a single stored record by 1-based record number, where `RR RR` is the big-endian record number.
- `AC 00 00 00 00` asks for multi-user status.
- `A1 8D 00 00 00` asks for console date.
- `A1 8E 00 00 00` asks for console time.
- `A1 FF 00 00 00 00` was observed as the physical treadmill response to a date query with the console in its current state.
- `AB 01 00 00 00` clears stored console data in the legacy sync flow. Avoid sending this in OpenLifeSpan unless the user explicitly enables a clear-after-import option.
- `AB 02 00 00 00` and `AB 02 01 00 00` appear to exit the legacy sync mode back to idle/pause.

Record format clues from legacy bytecode:

- Historical data records appear to be 25 bytes long.
- CRC uses Modbus RTU CRC16 over the first 23 bytes; CRC bytes are stored at offsets 23 and 24.
- Record count response starts with `AA AA`, then count high/low at offsets 2 and 3.
- Record fields include:
  - bytes 0-1: record tag
  - byte 3: day
  - byte 5: hour
  - byte 6: month/minute overlap in the legacy bytecode
  - byte 7: year offset from 2000/second overlap in the legacy bytecode
  - byte 8: heart rate
  - bytes 9-10: calories divided by 10
  - bytes 11-12: steps
  - byte 13: speed integral
  - byte 14: speed fraction
  - byte 15: level
  - bytes 16-17: distance divided by 1000
  - bytes 18-19: watts
  - byte 20: stop/run/pause/metric flags
  - bytes 21-22: record second
  - bytes 23-24: CRC
- The date/time byte mapping looks odd in bytecode because month/minute and year/second appear to overlap. Validate against live packets before building final import logic.

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

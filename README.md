# OpenLifeSpan

OpenLifeSpan is a clean-room, local-first companion app for LifeSpan treadmill owners who want to sync activity without relying on unsupported legacy apps or LifeSpan cloud services.

The initial target device is the LifeSpan TR-1200 DT3 treadmill console.

## Goals

- Discover and document the treadmill Bluetooth protocol.
- Sync treadmill activity directly to a modern Android app.
- Store workout history locally.
- Aggregate activity by day, week, month, and year.
- Provide richer trend analytics than the legacy app.
- Support import/export in open formats such as CSV and JSON.
- Avoid LifeSpan server communication entirely.
- Avoid copying proprietary code, branding, UI, or assets from legacy apps.

## Non-Goals

- No LifeSpan account login.
- No LifeSpan cloud sync.
- No bundled trackers, ads, or social SDKs.
- No redistribution of LifeSpan proprietary assets.

## Current Version

The first app version can:

- Stay in a BLE standby mode so it is ready when the console Bluetooth button is pressed.
- Sync the current console counters for distance, duration, calories, steps, max speed, and units.
- Save synced sessions locally on the phone.
- Show today's totals and a recent-session history.
- Compute average speed from synced distance and elapsed time.
- Manually clear stored console activity after confirmation.
- Manually restore or adjust treadmill speed with confirmation.
- Reset the BLE session deterministically when Android's GATT stack gets stuck.
- Keep an app-private debug log for troubleshooting.

## Project Phases

1. BLE discovery logger - done
   - Scan for nearby Bluetooth LE devices.
   - Identify the treadmill console by name, advertisement data, services, and characteristics.
   - Capture reads, writes, and notifications during treadmill sync.

2. Protocol decoder - in progress
   - Map raw packets to workout fields: duration, distance, calories, steps, speed, and timestamps.
   - Build repeatable fixtures from real capture logs.

3. Local activity app - in progress
   - Persist workouts locally.
   - Show daily, weekly, monthly, and yearly summaries.
   - Add trends, streaks, personal bests, and export/import.

4. Optional integrations
   - Health Connect export.
   - Google Fit export if still useful for the target Android versions.

## Development Notes

This repository intentionally starts with a minimal Android BLE logger. The logger exists to learn the treadmill protocol safely before building the polished activity tracker.

Anything learned from the legacy XAPK should be treated as behavioral compatibility research only. Do not copy decompiled source, proprietary assets, or branding.

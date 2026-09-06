# Clean-Room Notes

OpenLifeSpan should be developed as an independent implementation.

Acceptable reference material:

- Public treadmill manuals and public support documentation.
- Bluetooth observations captured from hardware owned by the developer/user.
- Android platform documentation.
- Open protocol documentation, if discovered.
- High-level behavioral observations of the legacy app, such as "it shows daily, weekly, monthly, yearly summaries."

Avoid:

- Copying decompiled legacy app source.
- Copying icons, images, names, strings, or layouts from the legacy app.
- Reusing embedded API keys, secrets, or server endpoints.
- Communicating with LifeSpan servers.

Protocol research should be captured in `docs/protocol-notes.md` and, once understood, implemented from first principles with tests against anonymized packet fixtures.

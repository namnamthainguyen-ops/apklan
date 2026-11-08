# Room TN20 interactive display runtime

Build with Windows PowerShell 5.1, .NET Framework 4.7.2 and its WPF assemblies:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File client-runtime/Build.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File client-runtime/Test.ps1
```

Outputs: `release/RoomTN20.Runtime-1.4.0.dll` and `release/RoomTN20.Player-1.4.0.exe`. Install them as `RoomTN20.Runtime.dll` beside the protected SYSTEM agent and `Display/RoomTN20.Player.exe` in the read/execute-only display subdirectory. The player is standalone and does not require the runtime DLL to be readable by the interactive user.

`Controller.Start(dataRoot, derivedManagementKey, deviceId, port, certificatePfxPath, playerExePath)` starts the TLS 1.2 upload listener and local display control. Use a protected empty-password PFX with a private key. The published pin is SHA-256 of the leaf certificate's DER bytes. The agent only starts this runtime when `runtimeDataRoot` is configured; power management remains available if D: or the runtime is unavailable.

`Snapshot()` returns `LockState`, `DesiredLocked`, `MediaVersion`, `MediaPort`, `MediaCertSha256`, and `PlayerCount`. Lock states are `locked`, `unlocked`, `pending`, and `ui-offline`; the initial media version is `none`. `SetLocked(bool)` persists the desired state and waits up to eight seconds for the current console player's rendered acknowledgement. No player, secure desktop or a timeout produces an error rather than claiming that the desktop was locked. Heartbeat acknowledgement expires after ten seconds.

The player runs as the signed-in standard user. The SYSTEM runtime attempts to launch it with that console user's token and retries after a crash. The installer can also register a limited per-user logon task. The named pipe only accepts the protected player's actual process path/session; the player checks pipe ownership by SYSTEM and session zero without requiring access to the privileged process executable. Pipe writes are asynchronous and bounded. No code intercepts or bypasses Ctrl+Alt+Del or Windows authentication. This is a room-use overlay, not Windows credential locking or a kiosk security boundary.

Local administrative recovery (run an elevated terminal):

```text
RoomTN20.Player.exe --emergency-unlock --data-root D:\RoomTN20Data
```

Only administrators can write that recovery request. The runtime polls it and clears the desired lock. Standard users have read/execute access to `Media` and `State`; only SYSTEM/Administrators may modify them. All existing descendants are reparse-checked and their ACLs normalized at startup. The runtime never changes UWF settings or reboots. D: must be an available unprotected data volume; it is not created or partitioned automatically.

Media protocol on TCP 17842: a maximum 32,768-byte JSON line followed by the raw files in playlist order. Header fields are `deviceId`, `timestamp`, `nonce`, `mediaVersion`, `totalBytes`, `payload` (Base64 exact UTF-8 playlist JSON), and `signature`. HMAC-SHA256 canonical text:

```text
media\n<deviceId>\n<unixTimestamp>\n<nonce>\n<mediaVersion>\n<totalBytes>\n<lowercaseSha256OfPlaylistBytes>
```

The signed playlist is `{schema:1,version:<32 lowercase hex>,files:[{id:<64 lowercase hex SHA256>,name:<display name>,type:<image/jpeg|image/png|video/mp4>,length:<bytes>,sha256:<same id>,durationSeconds:<integer>}]}`. Maximum 20 files, 128 MiB per file, 256 MiB total. Images require a duration of 1–3600 seconds; video duration zero plays until its end with a one-hour safety limit. Empty playlists clear advertisements. Filenames are generated from content hashes; client display names are never used as filesystem paths.

The server verifies authentication, a 120-second timestamp window, nonce replay, length, hash and media magic. It stages entirely on D:, publishes a complete version by atomic `current.json` replacement, and retains the current and previous good versions. Interrupted identical retries are idempotent. Open old MP4 files that cannot yet be pruned are retried on later uploads.

Client 1.4.0 advertises authenticated capability `mediasync`. To avoid resending existing content, add `transferMode:"delta-v1"` to the header and append `\ndelta-v1` to the above request canonical text before signing. The legacy full-transfer header and sequence remain supported for older APKs.

The plan response line is bounded to 65,536 bytes to allow 20 added and 20 removed Unicode display names. For delta mode, flush the header and wait for a signed plan before sending any file bytes. The server stages and verifies copies from the current/previous generation by exact length and SHA-256, then returns `{type:"media-plan",nonce,deviceId,version,payload,signature}`. `payload` is Base64 of exact UTF-8 JSON `{missing:[sha256Ids],removed:[{id,name}],added:[{id,name}]}`. Sign canonical text `media-plan\n<nonce>\n<deviceId>\n<version>\n<lowercaseSha256OfPlanBytes>`. The sender validates identity, signature, version, nonce, unique missing IDs as a subset of the requested playlist and removed IDs disjoint from it, and unique added IDs within it. Added/removed refer to the active playlist; missing refers to the bytes to transfer. Send only missing files in original playlist order, followed by the normal signed final result. Reorder/duration changes and clearing a playlist require zero media bytes when the required content is already present. A removed item leaves the active playlist immediately on successful promotion; the previous generation remains as one bounded backup. Corrupt cached copies are requested again. TLS pinning, request authentication and atomic promotion apply to both modes.

The result is one signed JSON line `{nonce,deviceId,version,status,message,signature}` where status is `accepted` or `rejected`; canonical signature text is `media-result\n<nonce>\n<deviceId>\n<version>\n<status>\n<message>`. A stored playlist acknowledgement is not proof of successful video decoding. WPF MP4 playback requires Windows media components/codecs; failed content displays the branded fallback. Test the actual IoT image, multiple monitors, logon/RDP and codec availability before rollout.

The test script only calls pure production playlist/authentication validators and the player's non-UI `--self-test`. It never starts the runtime, opens a listener/window, locks a workstation, installs software or changes UWF.

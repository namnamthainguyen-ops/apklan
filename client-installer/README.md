# Room TN20 Windows client installer

TN20 · Công ty TNHH TN20 · [20.com.vn](https://20.com.vn)

Build on Windows with Windows PowerShell 5.1 and .NET Framework 4.7.2 or newer:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\client-installer\Build.ps1
```

The output is one file: `release/RoomTN20-ClientSetup-1.2.4.exe`. It embeds the exact agent, installer core, signed updater and public trust key from `client/`, plus their SHA-256 values. No sidecar DLL or payload download is needed. The executable requests Administrator permission and is unsigned. The company metadata is product branding, not an Authenticode publisher signature.

Normal launch shows the installer. Enter the same eight-character room key used by the Android app. Printable ASCII symbols are accepted; spaces and non-ASCII characters are rejected. The key is passed to an in-process PowerShell runspace through bound parameters, never through command-line arguments or installer logs. Only the derived management key is saved by the agent.

The GUI installs the LAN power agent first. It then attempts to prepare the Windows UWF feature separately. An unsupported or unavailable UWF feature does not undo the successful power-agent installation. Preparing UWF does not protect C: or automatically restart the computer.

UWF preparation invokes native 64-bit DISM directly, checks feature state before/after enabling, and records diagnostics in the protected installation folder. The final client readiness check requires fresh authenticated discovery/state/version replies. A complete installation shows success and closes automatically after three seconds. Unsupported UWF on Windows Server/Home/Pro is an expected power-only installation: it reports the unsupported feature explicitly and still closes successfully. Actual feature-preparation failures on supported Windows or failed client readiness keep the installer open with a diagnostic message. Reinstall preserves the device ID and optional saved VPN scope.

Safe verification modes (require an elevated process because the same EXE contains the Administrator manifest):

- `--verify-payload` or `--self-test`: check embedded hashes and the room-key derivation vector; no agent installation, firewall changes, feature preparation or reboot.
- `--verify-ui`: construct and inspect the form without showing it or changing the system.
- `--preview` or `--ui-test`: show the normal form, but its Install button only validates the entered key and displays a preview result. It never installs anything.

For disposable Windows CI runners only:

```text
RoomTN20-ClientSetup-1.2.4.exe --ci-install --install-path <dedicated-directory> --task-name <unique-task> --port <1024..65535>
```

This mode reads exactly one room-key line from standard input. It installs the embedded agent using the supplied dedicated path/task/port and never prepares UWF or restarts the machine. The existing installer path guards, ACL restrictions and stable device ID behavior remain in effect.

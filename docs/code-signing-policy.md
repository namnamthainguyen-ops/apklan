# Code signing policy

Room TN20 is maintained by Công ty TNHH TN20, https://20.com.vn, and released under the [MIT License](../LICENSE).

## Current status

An application for the free SignPath Foundation open-source signing program was submitted on 2026-11-08. SignPath's form confirmed receipt. Approval and a publicly trusted Authenticode signature have **not** yet been obtained. Existing Windows releases must not be represented as publicly signed. The RSA signature on update manifests is a separate integrity mechanism; it does not establish Windows publisher trust. Self-signed certificates are not a substitute for a publicly trusted code-signing certificate.

If the application is approved, the project will publish the provider's required attribution and signing identity here. SignPath Foundation's free signing identity is SignPath Foundation, not a certificate issued in TN20's company name.

## Responsibilities and release process

- Repository maintainer, reviewer and signing approver: [namnamthainguyen-ops](https://github.com/namnamthainguyen-ops), owner of [apklan](https://github.com/namnamthainguyen-ops/apklan).
- Only Room TN20 files built from the repository's reviewed source and release workflow may be signed.
- Each request to an external signing provider requires the authorized maintainer's approval and the provider's applicable checks. Maintainers must meet the provider's MFA requirements.
- Private update-signing keys and APK keystores are kept outside this repository. Provider/HSM credentials must be stored as protected CI secrets, never committed.
- When Authenticode signing becomes available, final signed binary hashes must be calculated **after** signing, then included in the RSA-signed update manifest. Existing released artifacts must not be silently replaced.

## Privacy and system changes

Room TN20 sends authenticated discovery, control and advertising data only to the LAN/VPN computers configured by the operator. Room keys, Windows account passwords and advertising files are not uploaded to GitHub, TN20's website or a signing provider. Installation configures scheduled tasks, a protected application directory and scoped firewall rules. Optional UWF preparation uses Windows servicing; it does not freeze C: or reboot automatically.

Automatic update checks contact GitHub; GitHub can observe ordinary network request information such as the requester's IP address. See [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). A signing provider will receive public project information and the release artifacts submitted for signing. See [SignPath's conditions](https://signpath.org/terms.html) before applying.

The client installer is visible and requires administrative authorization. The advertising player displays only when the room-use lock is requested. Room TN20 does not provide covert remote desktop control or disable Windows security controls. Uninstallation instructions and the uninstall script are provided in the repository; advertising data on D: is preserved for the administrator.

Signing proves artifact origin and integrity according to the relevant trust mechanism. It does not guarantee that Windows SmartScreen or Android Play Protect will never display a warning.

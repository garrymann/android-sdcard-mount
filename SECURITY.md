# Security policy

Removable Storage Manager reads raw storage media, can claim USB devices, and in the `foss` flavor can
run privileged code through root. Bugs here can mean data loss or privilege escalation, so please
report them privately.

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private vulnerability reporting instead:

1. Open the repository's [Security tab](https://github.com/garrymann/android-sdcard-mount/security).
2. Choose **Report a vulnerability** and describe the issue.

Please include:

- the app version and flavor (`play` or `foss`), from the About screen;
- the device, Android version, and whether it is rooted (and with what);
- steps to reproduce, and a proof of concept if you have one;
- the impact you expect: data corruption, access beyond a granted volume, privilege escalation, etc.

You should get an acknowledgement within 7 days. Please allow time for a fix to be released before
disclosing publicly; we will agree a disclosure date with you and credit you unless you prefer
otherwise.

## Scope

In scope, among others:

- the `DocumentsProvider` exposing files outside a volume the user granted;
- path traversal or canonicalisation bypasses across the root service boundary;
- any root code path reachable from the `play` flavor;
- writes to a volume the app should have refused (encrypted, adoptable, unrecognised or
  inconsistent filesystems);
- malformed partition tables or filesystem images that crash the parser or cause out-of-bounds reads.

Out of scope: limitations imposed by Android itself (for example, that an unprivileged app cannot open
a block device), and issues that require an already-compromised root environment.

## Supported versions

The project is in early development. Only the latest commit on `main` and the latest release receive
security fixes.

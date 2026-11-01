# Installing the Mac receiver

## Build

Requires Go ≥ 1.26 and Xcode Command Line Tools:

```bash
./scripts/build_mac.sh          # produces build/shotsync
```

## Install as a login agent (recommended)

```bash
./build/shotsync install --destination "$HOME/Media/ShotSync"
```

This command:

1. Copies the binary to `~/.local/bin/shotsync` (no root required).
2. Writes a per-user launchd agent (`~/Library/LaunchAgents/com.shotsync.receiver.plist`)
   with `RunAtLoad` and `KeepAlive`, so the receiver starts at login and is
   restarted if it crashes.
3. Validates and creates the destination folder explicitly selected with
   `--destination`. There is no user-specific or implicit default path.
4. Loads and starts the agent.

## Commands

```bash
shotsync status      # installed/loaded state + config + destination
shotsync configure --destination "/absolute/path" # change destination
shotsync configure --receiver-name "Home receiver" # change display name
shotsync start       # start (or restart) the agent
shotsync stop        # stop the agent, keep installation
shotsync logs        # recent receiver logs
shotsync uninstall   # remove the agent + binary (state files kept)
shotsync gen-qr      # print a pairing QR in the terminal
shotsync devices     # list paired Android devices
shotsync unpair <id> # revoke a device token and unpair
shotsync menu        # run the menu-bar status app
```

## Configuration

The config file lives at
`~/Library/Application Support/ShotSync/config.json` and is re-read within
seconds of changes (port changes require a restart):

```json
{
  "port": 47831,
  "bind_addr": "0.0.0.0",
  "device_name": "ShotSync Receiver",
  "dest_dir": "/absolute/path/chosen/by/the/user",
  "max_size_mb": 500,
  "paused": false,
  "log_level": "info",
  "rate_limit_per_min": 300
}
```

- `dest_dir` — required absolute path selected during installation; where
  media is stored. Change it with the CLI or menu-bar folder chooser.
- `device_name` — privacy-safe display name shown during pairing. It defaults
  to `ShotSync Receiver`, never the computer hostname.
- `max_size_mb` — maximum accepted file size (default 500; large enough for
  long screen recordings).
- `rate_limit_per_min` — upload rate limit (protects against malfunctioning
  clients).
- `paused` — reject uploads (also toggleable from the menu bar).

State files (identity, TLS key, paired devices, transfer history) live in
the same directory. The TLS private key is stored with 0600 permissions;
device tokens live in the macOS Keychain.

## Menu bar app

`shotsync menu` runs a minimal menu-bar interface showing receiver status,
connected devices, the last received screenshot, and actions: open or change
the destination folder, set the receiver display name, show a pairing QR code,
pause receiving, edit advanced preferences, view logs, and quit. The receiver itself runs independently of
the menu app (via launchd) — quitting the menu app does not stop receiving.

### ShotSync.app

Release builds include `ShotSync.app`, which wraps the same binary. Drag it
to `/Applications` and open it: the menu appears in the menu bar with no
Dock icon (`LSUIElement`). The app does not install the receiver. Run
`shotsync install --destination …` once (from Homebrew or the release
tarball) for that.

### Open the menu at login (opt-in, off by default)

Check **Open Menu at Login** in the menu, or use the CLI:

```bash
shotsync login-item enable    # or: /Applications/ShotSync.app/Contents/MacOS/shotsync login-item enable
shotsync login-item status
shotsync login-item disable
```

`enable` writes a per-user launchd agent,
`~/Library/LaunchAgents/com.shotsync.menu.plist`, which runs
`<this binary> menu` at login (`RunAtLoad`, no `KeepAlive`, Aqua sessions
only). It does not start a second copy right away. `disable` deletes the file,
and a menu that is already running stays open until you quit it. Run
`enable` from the copy you want launched, normally the one inside
`ShotSync.app`. The receiver's own agent (`com.shotsync.receiver`) is separate
and is not affected. `shotsync uninstall` also removes the menu login item.

## Uninstall

```bash
shotsync uninstall          # keeps state so re-pairing is unnecessary
shotsync uninstall --purge  # also removes config, identity and history
```

## Tailscale

ShotSync does not install or manage Tailscale. To receive screenshots while
away from home, install Tailscale separately and set the Mac's Tailscale
hostname (e.g. `receiver-alias.tailnet.ts.net`) in the Android app settings. The
receiver binds `0.0.0.0` by default and accepts the same authenticated
traffic over the Tailscale interface. Never port-forward the receiver to the
public internet.

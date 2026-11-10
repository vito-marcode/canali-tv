# dtt-sync: the Raspberry Pi companion for Canali TV

Any always-on Linux machine with `adb` and Python 3 on the same network as the box will do; a Raspberry Pi is
the usual choice.

A small Python HTTP server (`dtt_sync.py`, standard library only) that gives Canali TV what the box can't give it:

- **Channel list.** It runs `adb backup` of the box's TV provider (`com.android.providers.tv`) and taps the
  "Allow" button on the box's backup confirmation screen by itself. It then reads `tv.db` and keeps the DVB
  channels.
- **Guide.** It downloads the Italian XMLTV (epgshare01 `IT1`) every 4 h and matches it to the channels by name,
  keeping 6 h back to 48 h ahead.
- **Logos** from `tv-logo/tv-logos` (`countries/italy`), matched by name and cached on the Pi.
- **Scan helper.** It enables `timvision.telemetry` for the box's channel scan, which crashes without it, and
  disables it afterwards.

## Endpoints (port 8765)

| | |
|---|---|
| `GET /channels.json` | Channel list, with radio flag, logo path and EPG id |
| `GET /epg.json` | Programmes from now − 6 h to now + 48 h, keyed by channel id |
| `GET /logos/<file>.png` | Cached logos |
| `GET /status` | Last sync, last EPG, last error, counts, box address |
| `POST /sync` | Export channels from the box, rebuild EPG, return the list (the app's *Sincronizza*) |
| `POST /epg` | Rebuild the EPG only |
| `POST /scan/prepare` | `pm enable timvision.telemetry` on the box |
| `POST /scan/done` | `pm disable-user --user 0 timvision.telemetry`, also run automatically 30 min after prepare |

## Install on the Pi

```sh
sudo apt install adb python3
mkdir -p ~/dtt
cp dtt_sync.py overrides.json ~/dtt/
adb connect <box ip>:5555            # accept the Pi's key on the TV once ("Always allow")
sudo cp dtt-sync.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now dtt-sync
curl http://localhost:8765/status
```

Before enabling it, edit `dtt-sync.service`: `User=` (the user whose `~/dtt` holds the script; `pi` in the file),
the path in `ExecStart=`, and `DTT_BOX=` (the box's address).
After changing `dtt_sync.py`, copy it to `~/dtt/` again and `sudo systemctl restart dtt-sync`.

Settings, as environment variables (add `Environment=` lines to the unit):

| Variable | Default |
|---|---|
| `DTT_BOX` | required: the box, as `<ip>:5555` |
| `DTT_PORT` | `8765` |
| `DTT_XMLTV` | `https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz` |

Files in `~/dtt/`: `channels_raw.json` (last export), `channels.json` and `epg.json` (what the app gets),
`logos/`, `overrides.json`.

## overrides.json

When a channel's name doesn't match the XMLTV or the logo set, map it by LCN:

```json
{"20": {"epg": "20.it", "logo": "20-it.png"}, "14": {"logo": "canale-italia-it.png"}}
```

## Notes

- A sync shows the backup confirmation screen on the TV for a few seconds; the Pi taps it. If the screen does not
  appear within 25 s (box asleep, ADB key not accepted), the sync fails with "backup confirmation screen did
  not appear".
- The box must keep ADB on port 5555 open for the Pi. That port is plaintext on the LAN: it's the price of this setup.
- Logs: `journalctl -u dtt-sync -f`.

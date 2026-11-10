#!/usr/bin/env python3
"""DTT channel-list + EPG companion for the TIM Box.

Channel list: Android TV provider exported with `adb backup` (confirm screen auto-accepted).
EPG + programme images: Italian XMLTV (epgshare01), matched to DVB channels by name.
Logos: tv-logo/tv-logos (countries/italy), matched by name, cached locally.

  GET  /channels.json        channel list (+ radio flag, logo path, epg id)
  GET  /epg.json             programmes from now-6h to now+48h, keyed by channel id
  GET  /logos/<file>.png     cached channel logos
  GET  /status               last sync / epg info
  POST /sync                 export channels from the box + rebuild EPG, returns channel list
  POST /epg                  rebuild EPG only (download XMLTV again)
  POST /scan/prepare         enable timvision.telemetry so Technicolor's channel scan doesn't crash
  POST /scan/done            disable it again (also automatic after 30 min)

Overrides (optional) ~/dtt/overrides.json: {"<lcn>": {"epg": "<xmltv id>", "logo": "<file.png>"}}
"""
import gzip, io, json, os, re, sqlite3, subprocess, tarfile, tempfile, threading, time, unicodedata, zlib
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BOX = os.environ.get("DTT_BOX", "")   # the box, "<ip>:5555" (required)
PORT = int(os.environ.get("DTT_PORT", "8765"))
XMLTV_URL = os.environ.get("DTT_XMLTV", "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz")
LOGO_INDEX = "https://api.github.com/repos/tv-logo/tv-logos/contents/countries/italy"
LOGO_RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/"
EPG_EVERY_S = 4 * 3600
DIR = os.path.expanduser("~/dtt")
OUT = os.path.join(DIR, "channels.json")
RAW = os.path.join(DIR, "channels_raw.json")
EPG = os.path.join(DIR, "epg.json")
LOGOS = os.path.join(DIR, "logos")
OVR = os.path.join(DIR, "overrides.json")
lock = threading.Lock()
state = {"last_sync": None, "last_epg": None, "last_error": None, "count": 0, "epg_channels": 0, "logos": 0}

UA = {"User-Agent": "dtt-sync/2.0"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def fetch(url, timeout=90):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read()


# ---------------------------------------------------------------- name matching

DROP = {"hd", "fhd", "uhd", "4k", "sd", "tv", "it", "italia"}  # 'tv'/'italia' dropped only as trailing noise, see norm()


def norm(name):
    s = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\btgr\b.*$", "", s)            # "Rai 3 TGR Marche" -> "rai 3"
    s = s.replace("+", " plus ")
    words = re.findall(r"[a-z0-9]+", s)
    while len(words) > 1 and words[-1] in DROP:  # strip trailing hd / tv / it ...
        words.pop()
    return "".join(words)


def load_overrides():
    try:
        return json.load(open(OVR))
    except Exception:
        return {}


# ---------------------------------------------------------------- channel export (adb backup)

def adb(*args, timeout=30):
    return subprocess.run(["adb", "-s", BOX, *args], capture_output=True, text=True, timeout=timeout)


def ensure_connected():
    subprocess.run(["adb", "connect", BOX], capture_output=True, text=True, timeout=15)
    if adb("get-state").stdout.strip() != "device":
        raise RuntimeError("box %s not reachable/authorized over adb" % BOX)


def confirm_backup_screen(deadline):
    while time.time() < deadline:
        time.sleep(1.5)
        if "backupconfirm" not in adb("shell", "dumpsys", "window").stdout.split("mCurrentFocus", 1)[-1][:200]:
            continue
        adb("shell", "uiautomator", "dump", "/sdcard/dtt_ui.xml")
        xml = adb("shell", "cat", "/sdcard/dtt_ui.xml").stdout
        m = re.search(r'resource-id="com\.android\.backupconfirm:id/button_allow"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return True
    return False


def export_channels():
    ensure_connected()
    with tempfile.TemporaryDirectory() as tmp:
        ab = os.path.join(tmp, "tv.ab")
        proc = subprocess.Popen(["adb", "-s", BOX, "backup", "-f", ab, "-noapk", "com.android.providers.tv"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if not confirm_backup_screen(time.time() + 25):
            proc.kill()
            raise RuntimeError("backup confirmation screen did not appear")
        proc.wait(timeout=120)
        raw = open(ab, "rb").read()
        if not raw.startswith(b"ANDROID BACKUP"):
            raise RuntimeError("invalid backup file (%d bytes)" % len(raw))
        off = 0
        for _ in range(4):
            off = raw.index(b"\n", off) + 1
        tar = tarfile.open(fileobj=io.BytesIO(zlib.decompress(raw[off:])))
        for m in tar.getmembers():  # tv.db + -wal + -shm must sit together
            base = os.path.basename(m.name)
            if base.startswith("tv.db"):
                open(os.path.join(tmp, base), "wb").write(tar.extractfile(m).read())
        con = sqlite3.connect(os.path.join(tmp, "tv.db"))
        con.row_factory = sqlite3.Row
        rows = con.execute("""
            select _id, input_id, type, service_type, original_network_id, transport_stream_id, service_id,
                   display_number, display_name, browsable, locked
            from channels where type like 'TYPE_DVB%' or input_id like '%dtvinput%'""").fetchall()
        con.close()
    chans = [{
        "id": r["_id"], "number": r["display_number"], "name": r["display_name"],
        "type": r["type"], "service_type": r["service_type"],
        "radio": r["service_type"] == "SERVICE_TYPE_AUDIO",
        "onid": r["original_network_id"], "tsid": r["transport_stream_id"], "sid": r["service_id"],
        "input_id": r["input_id"], "browsable": bool(r["browsable"]), "locked": bool(r["locked"]),
        "channel_uri": "content://android.media.tv/channel/%d" % r["_id"],
    } for r in rows]
    json.dump(chans, open(RAW, "w"), ensure_ascii=False)
    return chans


# ---------------------------------------------------------------- logos

def refresh_logos(chans):
    os.makedirs(LOGOS, exist_ok=True)
    try:
        index = [x["name"] for x in json.loads(fetch(LOGO_INDEX)) if x["name"].endswith(".png")]
        json.dump(index, open(os.path.join(LOGOS, "_index.json"), "w"))
    except Exception as e:
        log("logo index unavailable, using cache:", e)
        try:
            index = json.load(open(os.path.join(LOGOS, "_index.json")))
        except Exception:
            index = []
    by_norm = {}
    for f in index:
        by_norm.setdefault(norm(f[:-len("-it.png")] if f.endswith("-it.png") else f[:-4]), f)
    ovr = load_overrides()
    found = 0
    for c in chans:
        f = (ovr.get(str(c["number"])) or {}).get("logo") or by_norm.get(norm(c["name"]))
        if not f:
            continue
        path = os.path.join(LOGOS, f)
        if not os.path.exists(path):
            try:
                open(path, "wb").write(fetch(LOGO_RAW + f, 30))
            except Exception as e:
                log("logo download failed", f, e)
                continue
        c["logo"] = "/logos/" + f
        found += 1
    state["logos"] = found
    return found


# ---------------------------------------------------------------- EPG (XMLTV)

def xmltv_time(s):
    return int(datetime.strptime(s.strip(), "%Y%m%d%H%M%S %z").timestamp())


def build_epg(chans):
    data = fetch(XMLTV_URL, 180)
    root = ET.fromstring(gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data)
    count = {}
    for p in root.iterfind("programme"):
        count[p.get("channel")] = count.get(p.get("channel"), 0) + 1
    by_norm, icons = {}, {}
    for ch in root.iterfind("channel"):
        cid = ch.get("id")
        ic = ch.find("icon")
        if ic is not None:
            icons[cid] = ic.get("src")
        for dn in ch.findall("display-name") + [type("x", (), {"text": re.sub(r"\.it$", "", cid).replace(".", " ")})()]:
            k = norm(dn.text)
            if k and count.get(cid, 0) > count.get(by_norm.get(k), -1):
                by_norm[k] = cid                     # prefer the duplicate with most programmes
    ovr = load_overrides()
    wanted = {}
    for c in chans:
        eid = (ovr.get(str(c["number"])) or {}).get("epg") or by_norm.get(norm(c["name"]))
        if eid:
            c["epg_id"] = eid
            wanted.setdefault(eid, []).append(c["id"])
            if "logo" not in c and icons.get(eid):
                c["logo"] = icons[eid]               # fallback: XMLTV channel icon (absolute URL)
    now = int(time.time())
    lo, hi = now - 6 * 3600, now + 48 * 3600
    progs = {}
    for p in root.iterfind("programme"):
        eid = p.get("channel")
        if eid not in wanted:
            continue
        s, e = xmltv_time(p.get("start")), xmltv_time(p.get("stop"))
        if e < lo or s > hi:
            continue
        ic = p.find("icon")
        item = {"s": s, "e": e, "t": p.findtext("title") or "", "g": p.findtext("category") or "",
                "d": (p.findtext("desc") or "").strip(), "i": ic.get("src") if ic is not None else ""}
        sub = p.findtext("sub-title")
        if sub:
            item["st"] = sub
        progs.setdefault(eid, []).append(item)
    for v in progs.values():
        v.sort(key=lambda x: x["s"])
    out = {"generated": int(time.time()), "source": XMLTV_URL, "window": [lo, hi], "channels": {}}
    for eid, ids in wanted.items():
        for cid in ids:
            if eid in progs:
                out["channels"][str(cid)] = progs[eid]
    tmp = EPG + ".tmp"
    json.dump(out, open(tmp, "w"), ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, EPG)
    state.update(last_epg=time.strftime("%Y-%m-%dT%H:%M:%S%z"), epg_channels=len(out["channels"]))
    return len(out["channels"])


# ---------------------------------------------------------------- publish

def lcn_key(c):
    try:
        return float(c["number"])
    except (TypeError, ValueError):
        return 1e9


def publish(chans):
    chans = sorted(chans, key=lcn_key)
    doc = {"generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "box": BOX, "count": len(chans), "channels": chans}
    tmp = OUT + ".tmp"
    json.dump(doc, open(tmp, "w"), ensure_ascii=False, indent=1)
    os.replace(tmp, OUT)
    state["count"] = len(chans)
    return doc


def enrich_and_publish(chans):
    for c in chans:
        c.pop("logo", None); c.pop("epg_id", None)
        c["radio"] = c.get("service_type") == "SERVICE_TYPE_AUDIO"
    try:
        refresh_logos(chans)
    except Exception as e:
        log("logos failed:", e)
    try:
        n = build_epg(chans)
        log("epg: %d channels" % n)
    except Exception as e:
        state["last_error"] = "epg: %s" % e
        log("epg failed:", e)
    return publish(chans)


def full_sync():
    chans = export_channels()
    doc = enrich_and_publish(chans)
    state.update(last_sync=doc["generated"], last_error=None)
    return doc


def epg_only():
    try:
        chans = json.load(open(RAW))
    except Exception:
        chans = json.load(open(OUT))["channels"]
    return enrich_and_publish(chans)


def epg_loop():
    while True:
        try:
            if os.path.exists(RAW) or os.path.exists(OUT):
                with lock:
                    epg_only()
        except Exception as e:
            log("scheduled epg failed:", e)
        time.sleep(EPG_EVERY_S)


# ---------------------------------------------------------------- channel scan helper
#
# Technicolor's scan app (com.technicolor.android.dtvscan.project) sends a telemetry "trap" to
# content://timvision.telemetry/traps on start and crashes when that package is disabled (as it is
# on this debloated box). We enable it only for the duration of a scan, then restore DISABLED_USER.

TELEMETRY_PKG = "timvision.telemetry"
SCAN_GUARD_S = 30 * 60
scan_timer = None


def telemetry(enable):
    ensure_connected()
    cmd = ["shell", "pm", "enable", TELEMETRY_PKG] if enable else ["shell", "pm", "disable-user", "--user", "0", TELEMETRY_PKG]
    out = adb(*cmd).stdout.strip()
    log("telemetry %s: %s" % ("on" if enable else "off", out))
    return out


def scan_prepare():
    global scan_timer
    out = telemetry(True)
    if scan_timer:
        scan_timer.cancel()
    scan_timer = threading.Timer(SCAN_GUARD_S, scan_done)   # safety net if the app never reports back
    scan_timer.daemon = True
    scan_timer.start()
    return {"ok": True, "telemetry": out}


def scan_done():
    global scan_timer
    if scan_timer:
        scan_timer.cancel()
        scan_timer = None
    return {"ok": True, "telemetry": telemetry(False)}


# ---------------------------------------------------------------- HTTP

class H(BaseHTTPRequestHandler):
    def _bytes(self, code, body, ctype):
        gz = "gzip" in (self.headers.get("Accept-Encoding") or "") and len(body) > 2048 and not ctype.startswith("image/")
        if gz:
            body = gzip.compress(body, 6)
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        if gz:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=86400" if ctype.startswith("image/") else "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._bytes(code, json.dumps(obj, ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def _file(self, path, ctype):
        if not os.path.exists(path):
            return self._json(404, {"error": "not available yet"})
        self._bytes(200, open(path, "rb").read(), ctype)

    def do_GET(self):
        p = self.path.split("?", 1)[0]
        if p == "/channels.json":
            return self._file(OUT, "application/json; charset=utf-8")
        if p == "/epg.json":
            return self._file(EPG, "application/json; charset=utf-8")
        if p.startswith("/logos/"):
            name = os.path.basename(p)
            return self._file(os.path.join(LOGOS, name), "image/png")
        if p == "/status":
            return self._json(200, dict(state, box=BOX))
        self._json(404, {"error": "unknown path"})

    def do_POST(self):
        p = self.path.split("?", 1)[0]
        if p in ("/scan/prepare", "/scan/done"):
            try:
                return self._json(200, scan_prepare() if p == "/scan/prepare" else scan_done())
            except Exception as e:
                return self._json(500, {"error": str(e)})
        if p not in ("/sync", "/epg"):
            return self._json(404, {"error": "unknown path"})
        if not lock.acquire(blocking=False):
            return self._json(409, {"error": "sync already running"})
        try:
            self._json(200, full_sync() if p == "/sync" else epg_only())
        except Exception as e:
            state.update(last_error=str(e))
            self._json(500, {"error": str(e)})
        finally:
            lock.release()

    def log_message(self, fmt, *a):
        log(self.address_string(), fmt % a)


if __name__ == "__main__":
    if not BOX:
        raise SystemExit("set DTT_BOX to the box's address, e.g. DTT_BOX=192.168.1.50:5555")
    os.makedirs(DIR, exist_ok=True)
    log("dtt-sync 2.0 on :%d for box %s" % (PORT, BOX))
    threading.Thread(target=epg_loop, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()

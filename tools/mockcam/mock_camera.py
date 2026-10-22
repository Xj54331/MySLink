#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟索尼相机 DLNA 媒体服务器（联调/冒烟测试专用）。

仿真点（对照 ILCE-6300 实测行为）：
  - 根目录 PhotoRoot -> 两个日期容器；相机单次 Browse 截断 50 项
  - 每个照片三种资源：ORG_（原图）/ LRG_（大预览）/ TN_（缩略图）
  - RAW(.ARW) 只有转码预览，没有 ORG_ 资源（真实相机请求 ORG 会 406）
  - 只声明 image/jpeg（无视频）
  - dd.xml 里的地址用 10.0.2.2:8081（模拟器访问宿主机的固定别名）

用法: python mock_camera.py [port]
"""
import io
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

from PIL import Image, ImageDraw, ImageFont

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8081
HOST = f"10.0.2.2:{PORT}"          # 模拟器 -> 宿主机
BASE = f"http://{HOST}"
IMG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "img")

# 照片清单：(文件名, 日期ISO, 是否RAW, 渐变色)
PHOTOS = [
    ("DSC00437.ARW", "2026-03-27T09:12:04", True,  ("#8E7BB5", "#171427")),
    ("DSC00435.JPG", "2026-03-27T10:03:11", False, ("#C98B3A", "#241A10")),
    ("DSC00431.JPG", "2026-03-27T10:31:52", False, ("#4E7E96", "#101C24")),
    ("DSC00428.JPG", "2026-03-27T11:07:19", False, ("#3FA0A8", "#0E2225")),
    ("DSC00424.ARW", "2026-03-27T11:45:00", True,  ("#5E8B5B", "#121C13")),
    ("DSC00421.JPG", "2026-03-27T12:20:33", False, ("#B0574E", "#1E1210")),
    ("DSC00418.JPG", "2026-03-27T13:02:47", False, ("#9AA3AD", "#1B1F23")),
    ("DSC00415.JPG", "2026-03-27T14:18:25", False, ("#C96F8F", "#1D121A")),
    ("DSC00392.JPG", "2026-03-21T08:44:10", False, ("#D0A45C", "#2A2012")),
    ("DSC00389.JPG", "2026-03-21T09:15:39", False, ("#6E8FC9", "#131A2A")),
    ("DSC00385.JPG", "2026-03-21T10:02:55", False, ("#8E7BB5", "#171427")),
    ("DSC00381.JPG", "2026-03-21T11:37:02", False, ("#5E8B5B", "#121C13")),
    ("DSC00377.JPG", "2026-03-21T12:58:44", False, ("#C98B3A", "#241A10")),
    ("DSC00372.JPG", "2026-03-21T14:26:18", False, ("#4E7E96", "#101C24")),
]

GROUP_A = [p for p in PHOTOS if p[1].startswith("2026-03-27")]
GROUP_B = [p for p in PHOTOS if p[1].startswith("2026-03-21")]

SIZES = {  # 变体 -> (宽, 高, JPEG质量)
    "ORG": (3000, 2000, 92),
    "LRG": (1600, 1067, 88),
    "SM":  (640, 427, 85),
    "TN":  (256, 171, 80),
}

_gen_lock = threading.Lock()


def font(size):
    for name in ("arialbd.ttf", "arial.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            continue
    return ImageFont.load_default()


def make_jpeg(variant: str, stem: str, c1: str, c2: str) -> bytes:
    """按变体生成渐变 JPEG；文件已存在则复用。"""
    w, h, q = SIZES[variant]
    path = os.path.join(IMG_DIR, f"{variant}_{stem}.jpg")
    with _gen_lock:
        if os.path.exists(path) and os.path.getsize(path) > 0:
            with open(path, "rb") as f:
                return f.read()
        img = Image.new("RGB", (w, h))
        dr = ImageDraw.Draw(img)
        # 垂直渐变
        r1, g1, b1 = tuple(int(c1[i:i + 2], 16) for i in (1, 3, 5))
        r2, g2, b2 = tuple(int(c2[i:i + 2], 16) for i in (1, 3, 5))
        for y in range(h):
            t = y / max(1, h - 1)
            dr.line([(0, y), (w, y)], fill=(int(r1 + (r2 - r1) * t),
                                            int(g1 + (g2 - g1) * t),
                                            int(b1 + (b2 - b1) * t)))
        # 高光圆斑，像镜头光斑
        dr.ellipse([w * 0.55, h * 0.08, w * 0.95, h * 0.55], fill=(255, 236, 200, 0))
        img = Image.blend(img, Image.new("RGB", (w, h), (255, 240, 210)), 0.06)
        dr = ImageDraw.Draw(img)
        fs = max(14, w // 22)
        dr.text((w * 0.06, h * 0.06), stem, font=font(fs), fill=(255, 255, 255))
        dr.text((w * 0.06, h * 0.06 + fs * 1.4), f"{variant}  {w}x{h}",
                font=font(max(11, fs // 2)), fill=(230, 230, 230))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=q)
        data = buf.getvalue()
        os.makedirs(IMG_DIR, exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        return data


def item_xml(pid: str, name: str, date: str, raw: bool, colors, idx: int) -> str:
    stem = name.rsplit(".", 1)[0]
    # DlnaItem.isOriginal 判定是 URL 里含 "/ORG_"
    org = "" if raw else (
        f'<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_PN=JPEG_LRG"'
        f' size="{3000*2000*2}" resolution="3000x2000">'
        f'{BASE}/img/ORG_{idx}_{stem}.jpg</res>'
    )
    conv_proto = 'DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_PN=JPEG_LRG'
    tn_proto = 'http-get:*:image/jpeg:DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_PN=JPEG_TN'
    return (
        f'<item id="f{idx}" parentID="{pid}" restricted="1">'
        f"<dc:title>{escape(name)}</dc:title>"
        f"<upnp:class>object.item.imageItem.photo</upnp:class>"
        f"<dc:date>{date}</dc:date>"
        f'{org}'
        f'<res protocolInfo="{conv_proto}" size="{400000}" resolution="1600x1067">'
        f'{BASE}/img/LRG_{idx}_{stem}.jpg</res>'
        f'<res protocolInfo="{tn_proto}" size="{22000}" resolution="256x171">'
        f'{BASE}/img/TN_{idx}_{stem}.jpg</res>'
        f"</item>"
    )


def container_xml(cid: str, title: str, count: int) -> str:
    return (
        f'<container id="{cid}" parentID="0" restricted="1" childCount="{count}">'
        f"<dc:title>{escape(title)}</dc:title>"
        f"<upnp:class>object.container.storageFolder</upnp:class>"
        f"</container>"
    )


def didl_for(object_id: str) -> tuple:
    """返回 (didl_xml, total)。"""
    if object_id == "0":
        c = container_xml("1000", "2026-03-27", len(GROUP_A)) + \
            container_xml("1001", "2026-03-21", len(GROUP_B))
        body = f'<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">{c}</DIDL-Lite>'
        return body, 2
    if object_id == "1000":
        items = "".join(
            item_xml("1000", n, d, r, c, i) for i, (n, d, r, c) in enumerate(GROUP_A)
        )
    elif object_id == "1001":
        items = "".join(
            item_xml("1001", n, d, r, c, i + 100) for i, (n, d, r, c) in enumerate(GROUP_B)
        )
    else:
        items = ""
    total = len(GROUP_A) if object_id == "1000" else len(GROUP_B)
    body = f'<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">{items}</DIDL-Lite>'
    return body, total


DD_XML = f"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>MockCam(ILCE-6300)</friendlyName>
    <manufacturer>Sony</manufacturer>
    <modelName>ILCE-6300</modelName>
    <UDN>uuid:mock-ilce6300</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <controlURL>/control</controlURL>
        <eventSubURL>/event</eventSubURL>
        <SCPDURL>/scpd.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音
        sys.stderr.write("[mock] " + (fmt % args) + "\n")

    def _send(self, code: int, body: bytes, ctype: str = "text/xml; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/dd.xml"):
            self._send(200, DD_XML.encode("utf-8"))
            return
        m = re.match(r"^/img/(ORG|LRG|SM|TN)_(\d+)_(DSC\d+)\.jpg$", self.path)
        if m:
            variant, idx, stem = m.group(1), m.group(2), m.group(3)
            colors = ("#666666", "#101010")
            for n, d, r, c in PHOTOS:
                if n.startswith(stem):
                    colors = c
                    break
            self._send(200, make_jpeg(variant, f"{idx}_{stem}", *colors),
                       "image/jpeg")
            return
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", "replace")
        if "/control" in self.path and "Browse" in body:
            oid = re.search(r'<ObjectID>([^<]*)</ObjectID>', body)
            sc = re.search(r'<RequestedCount>(\d+)</RequestedCount>', body)
            object_id = oid.group(1).strip() if oid else "0"
            req_count = int(sc.group(1)) if sc else 0
            didl, total = didl_for(object_id)
            # 相机固件行为：单次最多返回 50 项
            n_ret = total if req_count == 0 else min(req_count, total)
            esc = escape(didl)
            soap = (
                '<?xml version="1.0"?>'
                '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
                "<s:Body>"
                '<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">'
                f"<Result>{esc}</Result>"
                f"<NumberReturned>{n_ret}</NumberReturned>"
                f"<TotalMatches>{total}</TotalMatches>"
                "<UpdateID>1</UpdateID>"
                "</u:BrowseResponse>"
                "</s:Body></s:Envelope>"
            )
            self._send(200, soap.encode("utf-8"))
            return
        self._send(500, b"unknown action", "text/plain")


def main():
    os.makedirs(IMG_DIR, exist_ok=True)
    print(f"[mock] 预生成缩略图与预览…", flush=True)
    # 预生成 TN/LRG，避免首屏请求阻塞
    for i, (n, d, r, c) in enumerate(PHOTOS):
        stem = f"{i}_{n.rsplit('.', 1)[0]}"
        make_jpeg("TN", stem, *c)
        make_jpeg("LRG", stem, *c)
    print(f"[mock] 模拟相机就绪: http://{HOST}/dd.xml  ({len(PHOTOS)} 张)", flush=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.serve_forever()


if __name__ == "__main__":
    main()

import re
import subprocess
import sys
import time

ADB = r"D:\andoridsdk\platform-tools\adb.exe"
SERIAL = "emulator-5554"
UI = r"E:\temp\ui.xml"


def adb(*args, timeout=30):
    r = subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    return r.stdout + r.stderr


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    adb("pull", "/sdcard/ui.xml", UI)
    with open(UI, encoding="utf-8", errors="replace") as f:
        return f.read()


def nodes(xml):
    """返回 (text, content-desc, bounds(x1,y1,x2,y2)) 列表"""
    out = []
    for m in re.finditer(r'<node[^>]*?/?>', xml):
        n = m.group(0)
        t = re.search(r'text="([^"]*)"', n)
        d = re.search(r'content-desc="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            out.append((
                t.group(1) if t else "",
                d.group(1) if d else "",
                tuple(int(x) for x in b.groups()),
                'clickable="true"' in n,
            ))
    return out


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def tap_text(xml, exact_text, fuzzy=False):
    for t, d, b, _ in nodes(xml):
        hay = t if t else d
        hit = (hay == exact_text) if not fuzzy else (exact_text in hay)
        if hit:
            x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            tap(x, y)
            return True
    return False


def shot(name):
    adb("shell", "screencap", "-p", "/sdcard/shot.png")
    adb("pull", "/sdcard/shot.png", rf"E:\dsh-work\screens\{name}")
    return rf"E:\dsh-work\screens\{name}"


def ensure_connected():
    """确保 App 在设备页（已连接 MockCam）。"""
    for attempt in range(3):
        xml = dump()
        if "MockCam" in xml:
            print("已在设备页")
            return True
        # 启动 App
        adb("shell", "am", "start", "-n", "com.sonnyapp/.MainActivity")
        time.sleep(5)
        xml = dump()
        if "MockCam" in xml:
            print("已在设备页(重启后)")
            return True
        # 展开手动连接
        for _ in range(6):
            xml = dump()
            if "设备描述地址" in xml:
                break
            tap_text(xml, "手动输入 SSID / 密码（备用）", fuzzy=True)
            time.sleep(0.6)
            adb("shell", "input", "swipe", "320", "950", "320", "500", "350")
            time.sleep(0.5)
        else:
            print("找不到地址输入区")
            continue

        # 找字段（包含标签文本的可点击大节点）
        xml = dump()
        lab = None
        for t, d, b, _ in nodes(xml):
            if t.startswith("设备描述地址"):
                lab = b
                break
        if not lab:
            print("标签没找到")
            continue
        for t, d, b, clk in nodes(xml):
            if clk and b[0] <= lab[0] and b[1] <= lab[1] and b[2] >= lab[2] and b[3] >= lab[3] and b[3] - b[1] > 80:
                tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
                break
        time.sleep(1.3)
        adb("shell", "input", "text", "http://10.0.2.2:8081/dd.xml")
        time.sleep(0.7)
        xml = dump()
        if "http://10.0.2.2:8081/dd.xml" not in xml:
            print("输入失败")
            continue
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.9)
        xml = dump()
        if not tap_text(xml, "按地址直连"):
            print("按钮没找到")
            continue
        time.sleep(9)
        xml = dump()
        if "MockCam" in xml:
            print("连接成功")
            return True
        print(f"第{attempt+1}次连接失败，页面文本:", [n[0] for n in nodes(xml) if n[0]][:8])
    return False


def main():
    if not ensure_connected():
        print("无法进入设备页，中止")
        sys.exit(1)

    # 切到相册
    xml = dump()
    if not tap_text(xml, "相册"):
        print("相册 tab 未找到")
    time.sleep(3)
    shot("10-album-debug.png")
    xml = dump()
    texts = [n[0] for n in nodes(xml) if n[0]]
    print("相册页文本:", texts[:12])


if __name__ == "__main__":
    main()

import subprocess

ADB = r"D:\andoridsdk\platform-tools\adb.exe"
SERIAL = "emulator-5554"


def run(args):
    r = subprocess.run([ADB, "-s", SERIAL] + args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout + r.stderr


# 用 App 完全一致的查询条件验证 MediaStore
where = "relative_path LIKE 'Pictures/SonnyLink%'"
out = run([
    "shell", "content", "query",
    "--uri", "content://media/external/images/media",
    "--projection", "_display_name:relative_path:datetaken:date_added:is_pending:_data",
    "--where", where,
])
print("== MediaStore 查询 ==")
print(out.strip() or "(空)")

# 再查所有行看 relative_path 实际值
out2 = run([
    "shell", "content", "query",
    "--uri", "content://media/external/images/media",
    "--projection", "_display_name:relative_path:is_pending",
])
print("\n== 全部图片行 ==")
for line in out2.splitlines():
    if "DSC" in line:
        print(line)

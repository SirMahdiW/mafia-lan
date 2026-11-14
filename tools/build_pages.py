#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ساخت نسخهٔ ایستای وب‌سایت بازی مافیا برای GitHub Pages.

این اسکریپت همان فایل واقعی `client.html` برنامه را کپی می‌کند، مسیرهای مطلق
دارایی‌ها را به مسیر نسبی تبدیل می‌کند (تا زیر آدرس مخزن کار کند) و یک
اعلان فارسی دربارهٔ اینکه «بازی چندگوشی» به سرور Kotlin نیاز دارد اضافه می‌کند.

هیچ‌چیز از منطق بازی، طراحی یا محتوای فارسی تغییر نمی‌کند.
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SITE = os.path.join(ROOT, "docs")

# مسیرهای مطلق → نسبی (برای GitHub Pages زیر آدرس /<repo>/)
REPLACEMENTS = [
    ("fetch('/data/roles.json'", "fetch('data/roles.json'"),
    ("fetch('/data/guides.json'", "fetch('data/guides.json'"),
    ("AU.src='/'+r.audio.file", "AU.src=r.audio.file"),
    ("AU.src='/audio/'+String(s.k)", "AU.src='audio/'+String(s.k)"),
    ("new EventSource('/events?t='", "new EventSource('events?t='"),
    ("post('/join'", "post('join'"),
    ("post('/local'", "post('local'"),
    ("post('/act'", "post('act'"),
    ("src=\"/'+r.images[0].file", "src=\"'+r.images[0].file"),
    ("src=\"/${r.images[0].file}", "src=\"${r.images[0].file}"),
]

NOTICE = (
    '<div id="ghp-note" style="max-width:480px;margin:10px auto 0;padding:10px 14px;'
    'border:1px solid rgba(255,93,80,.35);border-radius:16px;background:rgba(58,26,29,.85);'
    'color:#f6ecea;font-size:13px;line-height:1.9;text-align:center">'
    '<b>نسخهٔ ایستای وب‌سایت</b><br>'
    'کتاب نقش‌ها، تصاویر و صداها بدون سرور در دسترس‌اند. '
    'برای <b>بازی چندگوشی</b> برنامهٔ اندروید را روی گوشیِ میزبان نصب کنید؛ '
    'سرور بازی روی همان گوشی اجرا می‌شود و بازیکنان با هات‌اسپات به آن می‌پیوندند.'
    '</div>'
)


def main() -> int:
    if not os.path.isdir(ASSETS):
        print("پوشهٔ دارایی‌ها پیدا نشد: %s" % ASSETS, file=sys.stderr)
        return 1

    if os.path.isdir(SITE):
        shutil.rmtree(SITE)
    os.makedirs(SITE)

    # ۱) فایل اصلی رابط کاربری
    src_html = os.path.join(ASSETS, "client.html")
    with open(src_html, encoding="utf-8") as f:
        html = f.read()

    for old, new in REPLACEMENTS:
        if old not in html:
            print("هشدار: الگو پیدا نشد -> %s" % old, file=sys.stderr)
        html = html.replace(old, new)

    # ۲) اعلان فارسی (تنها تفاوت ظاهری با نسخهٔ برنامه)
    marker = '<body>'
    if marker in html:
        html = html.replace(marker, marker + NOTICE, 1)
    else:
        print("هشدار: تگ <body> پیدا نشد", file=sys.stderr)

    with open(os.path.join(SITE, "index.html"), "w", encoding="utf-8") as f:
        f.write(html)

    # ۲) دارایی‌ها (تصاویر، صداها و داده‌های فارسی)
    for name in ("data", "img", "audio"):
        src = os.path.join(ASSETS, name)
        if os.path.isdir(src):
            shutil.copytree(src, os.path.join(SITE, name))

    # ۳) فایل کمکی برای اینکه Pages مسیر /data/ را به index.html ندهد
    with open(os.path.join(SITE, ".nojekyll"), "w", encoding="utf-8") as f:
        f.write("")

    total = sum(
        os.path.getsize(os.path.join(dp, fn))
        for dp, _, fns in os.walk(SITE)
        for fn in fns
    )
    print("سایت ساخته شد: %s (%.1f مگابایت)" % (SITE, total / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())

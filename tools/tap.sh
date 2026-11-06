#!/bin/bash
adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
python3 - "$1" <<'PY'
import sys,re,subprocess
x=subprocess.check_output(['adb','shell','cat','/sdcard/u.xml']).decode(); t=sys.argv[1]
for m in re.finditer(r'<node[^>]*>',x):
    n=m.group(0); tx=re.search(r' text="([^"]*)"',n).group(1); cd=re.search(r'content-desc="([^"]*)"',n).group(1)
    if t in tx or t in cd:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]*)"',n).group(1))))
        subprocess.call(['adb','shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)]); print('tapped',tx or cd); sys.exit(0)
print('NOT FOUND',t); sys.exit(1)
PY

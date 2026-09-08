import os, sys, urllib.request, time

# 通过环境变量代理下载（HTTP_PROXY/HTTPS_PROXY），带重试与进度输出
url, dest = sys.argv[1], sys.argv[2]
os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
tmp = dest + '.part'
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 走 env proxy
req = urllib.request.Request(url, headers=headers)
last = 0
for attempt in range(1, 5):
    try:
        with opener.open(req, timeout=120) as r, open(tmp, 'wb') as f:
            total = 0
            while True:
                chunk = r.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)
                total += len(chunk)
                if total - last > (8 << 20):
                    last = total
                    print('progress %d MB' % (total // (1 << 20)), flush=True)
        os.replace(tmp, dest)
        print('DONE %s %d bytes' % (dest, os.path.getsize(dest)), flush=True)
        sys.exit(0)
    except Exception as e:
        print('attempt %d failed: %r' % (attempt, e), flush=True)
        time.sleep(3)
print('FAILED ' + url)
sys.exit(1)

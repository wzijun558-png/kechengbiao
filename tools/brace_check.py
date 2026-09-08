import glob

def scan(path):
    src = open(path, encoding='utf-8').read()
    depth = {'{': 0, '(': 0, '[': 0}
    pairs = {'}': '{', ')': '(', ']': '['}
    i, n = 0, len(src)
    state = 'code'  # code | line | block | str_d | str_s
    # 逐字符状态机去除注释与字符串后再统计括号
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state == 'code':
            if c == '/' and nxt == '/':
                state = 'line'; i += 2; continue
            if c == '/' and nxt == '*':
                state = 'block'; i += 2; continue
            if c == '"':
                state = 'str_d'; i += 1; continue
            if c == "'":
                state = 'str_s'; i += 1; continue
            if c in depth:
                depth[c] += 1
            elif c in pairs:
                depth[pairs[c]] -= 1
            i += 1
        elif state == 'line':
            if c == '\n': state = 'code'
            i += 1
        elif state == 'block':
            if c == '*' and nxt == '/': state = 'code'; i += 2; continue
            i += 1
        elif state == 'str_d':
            if c == '\\': i += 2; continue
            if c == '"': state = 'code'
            i += 1
        elif state == 'str_s':
            if c == '\\': i += 2; continue
            if c == "'": state = 'code'
            i += 1
    return depth

bad = []
for f in glob.glob(r'C:\Users\l\Desktop\dsh\CourseTableApp\app\src\**\*.kt', recursive=True):
    d = scan(f)
    if any(v != 0 for v in d.values()):
        bad.append((f, d))
print('IMBALANCED:', bad if bad else 'none')

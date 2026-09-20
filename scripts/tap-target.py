"""打印某个坐标落在哪个界面节点上。给 dev.sh 的 tap / taptext 用，防盲点误触。

用法：python tap-target.py <ui.xml> <x> <y>
找不到节点时输出「<未知：该坐标不在任何可点击节点内>」，仍然退出 0（不该拦住操作）。
"""
import re
import sys

# 这台机器的控制台是 GBK：print 中文/emoji 会 UnicodeEncodeError 直接崩掉
# （崩的正好是最该看见的那句警告）。统一按 UTF-8 输出，编码不了的字符替换而不是抛异常。
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

CHECKIN_HINTS = ('打卡', '一键', '完成', '勾选', 'checkbox', 'card')


def nodes(xml: str):
    for match in re.finditer(r'<node\b(.*?)/?>', xml, re.S):
        attrs = match.group(1)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', attrs)
        if not bounds:
            continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        text = re.search(r'\btext="([^"]*)"', attrs)
        desc = re.search(r'content-desc="([^"]*)"', attrs)
        cls = re.search(r'class="([^"]*)"', attrs)
        clickable = re.search(r'clickable="true"', attrs)
        yield {
            'box': (x1, y1, x2, y2),
            'label': (text.group(1) if text and text.group(1) else '')
                     or (desc.group(1) if desc else ''),
            'cls': cls.group(1).split('.')[-1] if cls else '?',
            'click': bool(clickable),
        }


def main() -> int:
    path, x, y = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    with open(path, encoding='utf-8', errors='replace') as handle:
        xml = handle.read()

    hits = [
        node for node in nodes(xml)
        if node['box'][0] <= x <= node['box'][2] and node['box'][1] <= y <= node['box'][3]
    ]
    if not hits:
        print('<未知：该坐标不在任何已 dump 的节点内>')
        return 0

    # 最小面积 = 最内层，才是真正接住这一指的那个 View
    inner = min(hits, key=lambda node: (node['box'][2] - node['box'][0]) * (node['box'][3] - node['box'][1]))
    x1, y1, x2, y2 = inner['box']
    print(f"{inner['cls']} 「{inner['label'] or '（无文字）'}」 "
          f"bounds=[{x1},{y1}][{x2},{y2}] clickable={inner['click']} "
          f"（外层共 {len(hits)} 层）")

    chain = ' / '.join(node['label'] for node in hits if node['label'])
    if any(hint in chain for hint in CHECKIN_HINTS):
        print('[!] 这条链上有"打卡/完成"类字样 —— 落点可能是写库操作，确认过再点')

    # 今天真正踩的坑不是点到按钮，而是点到**整张动作卡**：卡片整体是 combinedClickable，
    # 标签只有动作名，靠上面的关键词根本拦不住。所以再加一条：
    # 可点 + 占屏超过 5% = "整块都是热区"，点哪儿都是同一个动作。
    # 阈值按实测取：训练弹窗里那张动作卡约 984×200 ≈ 9.5% 屏（误触的就是它），
    # 而卡右下角的「一键打卡」按钮只有 171×60 ≈ 0.5% —— 5% 卡在两者之间。
    root = max(hits, key=lambda node: (node['box'][2] - node['box'][0]) * (node['box'][3] - node['box'][1]))
    root_area = max(1, (root['box'][2] - root['box'][0]) * (root['box'][3] - root['box'][1]))
    share = ((x2 - x1) * (y2 - y1)) / root_area
    if inner['click'] and share > 0.05:
        print(f'[!] 这是一块 {share:.0%} 屏的大热区且整块可点 —— 点它任意位置都是同一个动作')
    return 0


if __name__ == '__main__':
    sys.exit(main())

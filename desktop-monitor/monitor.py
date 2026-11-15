"""Windows desktop viewer for AssistStructure LAN broadcasts."""
import collections
import json
import math
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from protocol import PORT, Receiver

VERSION = '0.2.0'
STATUS = {
    'received_callbacks': '已收到结构回调', 'partial_callbacks': '部分回调',
    'no_callback_after_8_seconds': '系统未返回结构', 'screen_locked': '手机已锁屏 / 熄屏',
    'stream_stopped': '手机已停止采集', 'session_error': '助理会话失败',
    'structure_received': '有结构', 'null_structure': '结构为空', 'empty_structure': '无窗口',
    'read_error': '读取失败',
}


def label(value, limit=160):
    return str(value or '').replace('\n', ' ')[:limit]


def bounds(value):
    if not isinstance(value, list) or len(value) != 4:
        return None
    if not all(isinstance(x, (int, float)) and math.isfinite(x) and abs(x) < 1000000 for x in value):
        return None
    return value if value[2] > value[0] and value[3] > value[1] else None


class Monitor:
    def __init__(self, root, receiver):
        self.root, self.receiver = root, receiver
        self.devices = collections.OrderedDict()
        self.selected = None
        self.displayed = None
        self.paused = False
        self.nodes = {}
        self.draw_nodes = []
        self.selected_node = None
        self.loaded = set()
        root.title('Assist LAN Monitor ' + VERSION)
        root.geometry('1280x820')
        root.minsize(960, 640)
        style = ttk.Style()
        if 'clam' in style.theme_names():
            style.theme_use('clam')
        style.configure('.', font=('Microsoft YaHei UI', 10))
        style.configure('Treeview', rowheight=27)
        style.configure('Title.TLabel', font=('Microsoft YaHei UI', 19, 'bold'))
        bar = ttk.Frame(root, padding=(18, 12))
        bar.pack(fill='x')
        ttk.Label(bar, text='Assist · 页面结构监看', style='Title.TLabel').pack(side='left')
        ttk.Button(bar, text='导出当前 JSON', command=self.export).pack(side='right', padx=4)
        self.pause_button = ttk.Button(bar, text='暂停刷新', command=self.toggle_pause)
        self.pause_button.pack(side='right', padx=4)
        self.info = ttk.Label(root, text=f'正在监听 UDP {PORT} · 等待手机广播', padding=(18, 0, 18, 10))
        self.info.pack(fill='x')
        panes = ttk.Panedwindow(root, orient='horizontal')
        panes.pack(fill='both', expand=True, padx=14)
        left = ttk.Frame(panes, width=270)
        panes.add(left, weight=1)
        ttk.Label(left, text='局域网设备', padding=5).pack(anchor='w')
        self.device_tree = ttk.Treeview(left, columns=('ip', 'state'), show='tree headings', height=7)
        self.device_tree.heading('#0', text='设备')
        self.device_tree.heading('ip', text='地址')
        self.device_tree.heading('state', text='状态')
        self.device_tree.column('#0', width=120, minwidth=70)
        self.device_tree.column('ip', width=105, minwidth=80)
        self.device_tree.column('state', width=90, minwidth=75)
        self.device_tree.pack(fill='x')
        self.device_tree.bind('<<TreeviewSelect>>', self.choose_device)
        self.device_info = ttk.Label(left, text='手机开启“连续采集并广播”后自动出现。', wraplength=290, padding=8)
        self.device_info.pack(fill='x')
        ttk.Label(left, text='位置轮廓（控件估计边界）', padding=5).pack(anchor='w')
        self.canvas = tk.Canvas(left, background='#e8eef5', highlightthickness=0, width=310)
        self.canvas.pack(fill='both', expand=True)
        self.canvas.bind('<Configure>', lambda event: self.draw())
        self.canvas.bind('<Button-1>', self.pick_canvas)
        right = ttk.Panedwindow(panes, orient='vertical')
        panes.add(right, weight=3)
        upper = ttk.Frame(right)
        right.add(upper, weight=3)
        self.page_info = ttk.Label(upper, text='等待结构数据', wraplength=850, padding=8)
        self.page_info.pack(fill='x')
        tree_frame = ttk.Frame(upper)
        tree_frame.pack(fill='both', expand=True)
        self.tree = ttk.Treeview(tree_frame, columns=('text', 'id'), show='tree headings')
        self.tree.heading('#0', text='控件层级 / 类名')
        self.tree.heading('text', text='文字 / 描述')
        self.tree.heading('id', text='资源 ID')
        self.tree.column('#0', width=280)
        self.tree.column('text', width=240)
        self.tree.column('id', width=250)
        scroll = ttk.Scrollbar(tree_frame, orient='vertical', command=self.tree.yview)
        horizontal = ttk.Scrollbar(tree_frame, orient='horizontal', command=self.tree.xview)
        self.tree.configure(yscrollcommand=scroll.set, xscrollcommand=horizontal.set)
        self.tree.grid(row=0, column=0, sticky='nsew')
        scroll.grid(row=0, column=1, sticky='ns')
        horizontal.grid(row=1, column=0, sticky='ew')
        tree_frame.rowconfigure(0, weight=1)
        tree_frame.columnconfigure(0, weight=1)
        self.tree.bind('<<TreeviewOpen>>', self.expand_node)
        self.tree.bind('<<TreeviewSelect>>', self.choose_node)
        lower = ttk.Frame(right)
        right.add(lower, weight=1)
        ttk.Label(lower, text='节点详情 · 点击树节点或轮廓查看', padding=6).pack(anchor='w')
        self.detail = tk.Text(lower, height=10, wrap='word', font=('Consolas', 10), background='#f8fafc', relief='flat')
        self.detail.pack(fill='both', expand=True)
        self.set_detail('手机与电脑连接同一局域网。\n\n首次运行如有 Windows 防火墙提示，请允许此程序访问专用网络。\n页面展示来自控件结构，不是屏幕截图。')
        self.footer = ttk.Label(root, padding=(18, 8))
        self.footer.pack(fill='x')
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.after(100, self.poll)

    def set_detail(self, text):
        self.detail.configure(state='normal')
        self.detail.delete('1.0', 'end')
        self.detail.insert('1.0', text)
        self.detail.configure(state='disabled')

    def poll(self):
        changed = False
        for frame in self.receiver.take_updates():
            key = frame['device']
            self.devices[key] = frame
            self.devices.move_to_end(key)
            if not self.device_tree.exists(key):
                self.device_tree.insert('', 'end', iid=key)
            if self.selected is None:
                self.selected = key
                self.device_tree.selection_set(key)
            if self.selected == key:
                changed = True
            while len(self.devices) > 32:
                oldest, _ = self.devices.popitem(last=False)
                self.device_tree.delete(oldest)
                if self.selected == oldest:
                    self.selected = key
                    self.device_tree.selection_set(key)
                    changed = True
        now = time.monotonic()
        for key, frame in self.devices.items():
            data = frame['data']
            age = now - frame['received']
            try:
                threshold = max(5, min(60, float(data.get('intervalMs', 1000)) / 1000 + 12))
            except (ValueError, TypeError):
                threshold = 13
            state = '已停止' if data.get('status') == 'stream_stopped' else ('未更新' if age > threshold else '在线')
            self.device_tree.item(key, text=label(data.get('model') or key[:8], 25), values=(frame['ip'], f'{state} {age:.0f}s'))
        if changed and not self.paused:
            self.render()
        if self.displayed:
            age = now - self.displayed['received']
            self.info.configure(text=f'UDP {PORT} · 当前画面距接收 {age:.1f} 秒' + (' · 已暂停刷新' if self.paused else ' · 自动刷新'))
        stats = self.receiver.reassembler
        self.footer.configure(text=f'完整帧 {self.receiver.frames}　不完整帧丢弃 {stats.dropped}　无效包 {stats.invalid}　|　{self.receiver.error or "等待当前帧分片齐全后刷新；丢包时等待下一帧"}')
        self.root.after(150, self.poll)

    def choose_device(self, _event=None):
        selected = self.device_tree.selection()
        if selected:
            self.selected = selected[0]
            self.render()

    def toggle_pause(self):
        self.paused = not self.paused
        self.pause_button.configure(text='继续刷新' if self.paused else '暂停刷新')
        if not self.paused:
            self.render()

    def render(self):
        frame = self.devices.get(self.selected)
        if not frame:
            return
        opened = {key for key in self.loaded if self.tree.exists(key) and self.tree.item(key, 'open')}
        selected = self.tree.selection()
        selected = selected[0] if selected else None
        self.displayed = frame
        data = frame['data']
        self.tree.delete(*self.tree.get_children())
        self.nodes.clear()
        self.loaded.clear()
        self.draw_nodes.clear()
        self.selected_node = None
        activities = data.get('activities', [])
        total = sum(a.get('nodeCount', 0) for a in activities if isinstance(a.get('nodeCount', 0), int))
        self.device_info.configure(text=f'{label(data.get("manufacturer"))} {label(data.get("model"))}\n{frame["ip"]} · #{data.get("sequence")}\n采集耗时：{data.get("captureDurationMs", "—")} ms\n压缩数据：{frame["compressedBytes"] / 1024:.1f} KiB\n设备 ID：{frame["device"][:8]}')
        self.page_info.configure(text=f'{STATUS.get(data.get("status"), label(data.get("status")))} · 节点 {total}\n{label(data.get("capturedAt"))}  {label(data.get("message"))}')
        windows = []
        for ai, activity in enumerate(activities):
            parent = f'a{ai}'
            name = activity.get('activity') or '未知页面'
            self.tree.insert('', 'end', iid=parent, text=label(name), values=(STATUS.get(activity.get('status'), activity.get('status')), ''), open=True)
            for wi, window in enumerate(activity.get('windows', [])):
                key = f'{parent}/w{wi}'
                self.tree.insert(parent, 'end', iid=key, text=f'窗口 {wi} · {label(window.get("title"))}', open=True)
                node_key = key + '/0'
                stack = [(node_key, window['root'])]
                while stack:
                    path, node = stack.pop()
                    self.nodes[path] = node
                    children = node.get('children', [])
                    stack.extend((f'{path}/{i}', child) for i, child in reversed(list(enumerate(children))))
                self.add_node(key, node_key, opened, depth=0)
                windows.append(window)
        self.window_bounds = bounds(windows[0].get('bounds')) if windows else None
        if windows:
            self.draw_nodes = [(key, node) for key, node in self.nodes.items() if key.startswith('a0/w0/')]
        if selected in self.nodes:
            self.reveal(selected)
        else:
            self.set_detail(json.dumps({key: value for key, value in data.items() if key != 'activities'}, ensure_ascii=False, indent=2))
        self.draw()

    def add_node(self, parent, key, opened, depth=9):
        node = self.nodes[key]
        self.tree.insert(parent, 'end', iid=key, text=label(node.get('className') or '(节点)', 120),
                         values=(label(node.get('text') or node.get('contentDescription')), label(node.get('resourceId'))))
        if node.get('children'):
            if key in opened or depth < 1:
                self.tree.item(key, open=True)
                self.loaded.add(key)
                for i, _ in enumerate(node['children']):
                    self.add_node(key, f'{key}/{i}', opened, depth + 1)
            else:
                self.tree.insert(key, 'end', iid=key + '/pending', text='展开加载…')

    def expand_node(self, _event=None):
        key = self.tree.focus()
        self.load_children(key)

    def load_children(self, key):
        node = self.nodes.get(key)
        if not node or key in self.loaded:
            return
        self.tree.delete(*self.tree.get_children(key))
        self.loaded.add(key)
        for i, _ in enumerate(node.get('children', [])):
            self.add_node(key, f'{key}/{i}', set())

    def reveal(self, key):
        parts = key.split('/')
        for depth in range(3, len(parts)):
            parent = '/'.join(parts[:depth])
            if self.tree.exists(parent):
                self.load_children(parent)
                self.tree.item(parent, open=True)
        if self.tree.exists(key):
            self.tree.selection_set(key)
            self.tree.see(key)
            self.selected_node = key
            self.set_detail(json.dumps({k: v for k, v in self.nodes[key].items() if k != 'children'}, ensure_ascii=False, indent=2))

    def choose_node(self, _event=None):
        selected = self.tree.selection()
        if selected and selected[0] in self.nodes:
            self.selected_node = selected[0]
            node = self.nodes[self.selected_node]
            self.set_detail(json.dumps({k: v for k, v in node.items() if k != 'children'}, ensure_ascii=False, indent=2))
            self.draw()

    def draw(self):
        self.canvas.delete('all')
        box = getattr(self, 'window_bounds', None)
        if not box:
            self.canvas.create_text(20, 25, anchor='nw', text='尚无可绘制的窗口结构', fill='#475569')
            return
        width, height = box[2] - box[0], box[3] - box[1]
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        scale = min(max(1, cw - 24) / width, max(1, ch - 24) / height)
        ox, oy = (cw - width * scale) / 2, (ch - height * scale) / 2
        self.transform = (scale, ox - box[0] * scale, oy - box[1] * scale)
        self.canvas.create_rectangle(ox, oy, ox + width * scale, oy + height * scale, fill='white', outline='#94a3b8')
        shown = 0
        for key, node in self.draw_nodes:
            rect = bounds(node.get('boundsOnScreenEstimate'))
            if not rect or node.get('visibility', 0) != 0:
                continue
            if shown >= 800 and key != self.selected_node:
                continue
            shown += 1
            s, tx, ty = self.transform
            coords = [rect[0] * s + tx, rect[1] * s + ty, rect[2] * s + tx, rect[3] * s + ty]
            color = '#dc2626' if key == self.selected_node else ('#2563eb' if node.get('clickable') else '#cbd5e1')
            self.canvas.create_rectangle(*coords, outline=color, width=2 if key == self.selected_node else 1)
            text = label(node.get('text'), 22)
            if text and shown < 100 and coords[2] - coords[0] > 30 and coords[3] - coords[1] > 12:
                self.canvas.create_text(coords[0] + 2, coords[1] + 2, text=text, anchor='nw', fill='#0f172a', font=('Microsoft YaHei UI', 8))

    def pick_canvas(self, event):
        if not hasattr(self, 'transform'):
            return
        scale, tx, ty = self.transform
        x, y = (event.x - tx) / scale, (event.y - ty) / scale
        matches = []
        for key, node in self.draw_nodes:
            rect = bounds(node.get('boundsOnScreenEstimate'))
            if rect and node.get('visibility', 0) == 0 and rect[0] <= x <= rect[2] and rect[1] <= y <= rect[3]:
                matches.append(((rect[2] - rect[0]) * (rect[3] - rect[1]), key))
        if matches:
            self.reveal(min(matches)[1])
            self.draw()

    def export(self):
        if not self.displayed:
            messagebox.showinfo('尚无数据', '先在手机上开始连续采集。')
            return
        data = self.displayed['data']
        filename = filedialog.asksaveasfilename(defaultextension='.json', initialfile=f'assist-{self.displayed["device"][:8]}-{data.get("sequence")}.json', filetypes=[('JSON', '*.json')])
        if filename:
            try:
                with open(filename, 'w', encoding='utf-8') as output:
                    json.dump(data, output, ensure_ascii=False, indent=2)
            except OSError as exc:
                messagebox.showerror('保存失败', str(exc))

    def close(self):
        self.receiver.close()
        self.root.destroy()


def main():
    root = tk.Tk()
    try:
        receiver = Receiver()
    except OSError as exc:
        root.withdraw()
        messagebox.showerror('无法监听', f'UDP {PORT} 启动失败：{exc}\n请检查是否已打开另一份监看程序。')
        root.destroy()
        return
    Monitor(root, receiver)
    root.mainloop()


if __name__ == '__main__':
    main()

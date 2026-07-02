#!/usr/bin/env python3
"""
Conect VCam — Windows remote control for Virtual Cam Android app.

Requirements:
  pip install -r requirements.txt

Usage:
  1. Connect Android phone via USB, enable USB Debugging
  2. Click "ADB Forward" (or run: adb forward tcp:7979 tcp:7979)
  3. Open Virtual Cam app on phone -> Enable Link -> copy token
  4. Paste token here, click Connect
  5. Use sliders/buttons to control zoom/pan/rotation/mirror in real-time
"""

import tkinter as tk
from tkinter import ttk, messagebox, font as tkfont
import socket
import json
import subprocess
import threading
import sys
import os

PORT = 7979
APP_TITLE = "Conect VCam"
BG = "#0d1117"
CARD = "#161b22"
ACCENT = "#4F8EF7"
GREEN = "#22c55e"
RED = "#ef4444"
FG = "#e6edf3"
FG2 = "#8b949e"


class ConnectVcam:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("440x700")
        self.root.resizable(False, False)
        self.root.configure(bg=BG)
        self._set_icon()

        # State
        self.sock: socket.socket | None = None
        self.connected = False
        self.send_lock = threading.Lock()
        self.pan_x = 0
        self.pan_y = 0
        self.rotation = 0
        self.mirror_state = False

        # Tk vars
        self.host_var  = tk.StringVar(value="localhost")
        self.port_var  = tk.IntVar(value=PORT)
        self.token_var = tk.StringVar()
        self.zoom_var  = tk.DoubleVar(value=1.0)
        self.scale_var = tk.DoubleVar(value=1.0)

        self._build_ui()

    def _set_icon(self):
        try:
            self.root.iconbitmap(default="icon.ico")
        except Exception:
            pass

    # ── UI ────────────────────────────────────────────────────────────────

    def _build_ui(self):
        # Title bar
        title_frame = tk.Frame(self.root, bg=BG, pady=12)
        title_frame.pack(fill="x", padx=18)
        tk.Label(title_frame, text="🔗  Conect VCam", bg=BG, fg=FG,
                 font=("Segoe UI", 16, "bold")).pack(side="left")
        self.lbl_status = tk.Label(title_frame, text="● Disconnected",
                                   bg=BG, fg=RED, font=("Segoe UI", 9))
        self.lbl_status.pack(side="right", pady=4)

        tk.Frame(self.root, bg="#21262d", height=1).pack(fill="x")

        # Scrollable main area
        canvas = tk.Canvas(self.root, bg=BG, highlightthickness=0)
        scrollbar = ttk.Scrollbar(self.root, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)

        self.main_frame = tk.Frame(canvas, bg=BG)
        win_id = canvas.create_window((0, 0), window=self.main_frame, anchor="nw")

        def _on_frame_configure(e):
            canvas.configure(scrollregion=canvas.bbox("all"))

        def _on_canvas_configure(e):
            canvas.itemconfig(win_id, width=e.width)

        self.main_frame.bind("<Configure>", _on_frame_configure)
        canvas.bind("<Configure>", _on_canvas_configure)

        # Mouse wheel scrolling
        def _on_mousewheel(e):
            canvas.yview_scroll(int(-1 * (e.delta / 120)), "units")

        canvas.bind_all("<MouseWheel>", _on_mousewheel)

        self._build_connection_card()
        self._build_zoom_card()
        self._build_scale_card()
        self._build_pan_card()
        self._build_rotate_mirror_card()
        self._build_instructions()

    def _card(self, title: str) -> tk.Frame:
        outer = tk.Frame(self.main_frame, bg=CARD, bd=0, relief="flat")
        outer.pack(fill="x", padx=14, pady=6)
        tk.Label(outer, text=title, bg=CARD, fg=FG2,
                 font=("Segoe UI", 9, "bold")).pack(anchor="w", padx=12, pady=(8, 4))
        inner = tk.Frame(outer, bg=CARD)
        inner.pack(fill="x", padx=12, pady=(0, 10))
        return inner

    def _btn(self, parent, text, cmd, color=ACCENT, fg=FG, width=10):
        b = tk.Button(parent, text=text, command=cmd, bg=color, fg=fg,
                      relief="flat", padx=8, pady=6, width=width,
                      font=("Segoe UI", 9), cursor="hand2",
                      activebackground=color, activeforeground=fg)
        b.pack(side="left", padx=4, pady=2)
        return b

    def _build_connection_card(self):
        f = self._card("CONNECTION / الاتصال")

        row = tk.Frame(f, bg=CARD); row.pack(fill="x", pady=2)
        tk.Label(row, text="Host:", bg=CARD, fg=FG2, width=7, anchor="e").pack(side="left")
        tk.Entry(row, textvariable=self.host_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=22).pack(side="left", padx=4)

        row2 = tk.Frame(f, bg=CARD); row2.pack(fill="x", pady=2)
        tk.Label(row2, text="Port:", bg=CARD, fg=FG2, width=7, anchor="e").pack(side="left")
        tk.Entry(row2, textvariable=self.port_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=10).pack(side="left", padx=4)

        row3 = tk.Frame(f, bg=CARD); row3.pack(fill="x", pady=2)
        tk.Label(row3, text="Token:", bg=CARD, fg=FG2, width=7, anchor="e").pack(side="left")
        tk.Entry(row3, textvariable=self.token_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=14,
                 font=("Consolas", 12, "bold")).pack(side="left", padx=4)

        btns = tk.Frame(f, bg=CARD); btns.pack(fill="x", pady=(8, 2))
        self._btn(btns, "⚡ ADB Forward", self._adb_forward, color="#21262d", width=14)
        self.btn_connect = self._btn(btns, "🔌 Connect", self._toggle_connect, color=ACCENT, width=14)

    def _build_zoom_card(self):
        f = self._card("ZOOM / تقريب")

        header = tk.Frame(f, bg=CARD); header.pack(fill="x")
        tk.Label(header, text="Digital Zoom:", bg=CARD, fg=FG2,
                 font=("Segoe UI", 9)).pack(side="left")
        self.lbl_zoom = tk.Label(header, text="1.0×", bg=CARD, fg=ACCENT,
                                 font=("Segoe UI", 10, "bold"))
        self.lbl_zoom.pack(side="right")

        ttk.Scale(f, from_=1.0, to=5.0, orient="horizontal",
                  variable=self.zoom_var,
                  command=self._on_zoom).pack(fill="x", pady=4)

        btns = tk.Frame(f, bg=CARD); btns.pack()
        self._btn(btns, "1× Reset", lambda: self._set_zoom(1.0), color="#21262d", width=9)
        self._btn(btns, "2×", lambda: self._set_zoom(2.0), color="#21262d", width=5)
        self._btn(btns, "3×", lambda: self._set_zoom(3.0), color="#21262d", width=5)
        self._btn(btns, "5×", lambda: self._set_zoom(5.0), color="#21262d", width=5)

    def _build_scale_card(self):
        f = self._card("SCALE / حجم الإطار")

        header = tk.Frame(f, bg=CARD); header.pack(fill="x")
        tk.Label(header, text="Frame Fill Scale:", bg=CARD, fg=FG2,
                 font=("Segoe UI", 9)).pack(side="left")
        self.lbl_scale = tk.Label(header, text="100%", bg=CARD, fg=GREEN,
                                  font=("Segoe UI", 10, "bold"))
        self.lbl_scale.pack(side="right")

        ttk.Scale(f, from_=0.3, to=2.0, orient="horizontal",
                  variable=self.scale_var,
                  command=self._on_scale).pack(fill="x", pady=4)

        btns = tk.Frame(f, bg=CARD); btns.pack()
        self._btn(btns, "100%", lambda: self._set_scale(1.0), color="#21262d", width=9)
        self._btn(btns, "50%",  lambda: self._set_scale(0.5), color="#21262d", width=5)
        self._btn(btns, "150%", lambda: self._set_scale(1.5), color="#21262d", width=7)
        self._btn(btns, "200%", lambda: self._set_scale(2.0), color="#21262d", width=7)

    def _build_pan_card(self):
        f = self._card("PAN / تحريك")

        grid = tk.Frame(f, bg=CARD); grid.pack(pady=4)

        def pb(text, r, c, cmd):
            tk.Button(grid, text=text, command=cmd, bg="#21262d", fg=FG,
                      relief="flat", width=4, height=2, font=("Segoe UI", 11),
                      cursor="hand2", activebackground=ACCENT, activeforeground=FG
                      ).grid(row=r, column=c, padx=3, pady=3)

        pb("▲", 0, 1, lambda: self._pan(0, -50))
        pb("◀", 1, 0, lambda: self._pan(-50, 0))
        pb("⌖", 1, 1, self._pan_reset)
        pb("▶", 1, 2, lambda: self._pan(50, 0))
        pb("▼", 2, 1, lambda: self._pan(0, 50))

        # Pan fine/coarse row
        fine = tk.Frame(f, bg=CARD); fine.pack(pady=(4, 0))
        tk.Label(fine, text="Step:", bg=CARD, fg=FG2, font=("Segoe UI", 8)).pack(side="left")
        self.pan_step = tk.IntVar(value=50)
        for v, lbl in [(20, "Fine"), (50, "Med"), (100, "Coarse")]:
            tk.Radiobutton(fine, text=lbl, variable=self.pan_step, value=v,
                           bg=CARD, fg=FG2, selectcolor=CARD,
                           font=("Segoe UI", 8), activebackground=CARD).pack(side="left", padx=4)

        self.lbl_pan = tk.Label(f, text="Pan: 0, 0", bg=CARD, fg=FG2,
                                font=("Segoe UI", 8))
        self.lbl_pan.pack()

    def _build_rotate_mirror_card(self):
        f = self._card("ROTATE & MIRROR / تدوير وعكس")

        row = tk.Frame(f, bg=CARD); row.pack(fill="x")
        tk.Label(row, text="Rotation:", bg=CARD, fg=FG2,
                 font=("Segoe UI", 9)).pack(side="left")
        self.lbl_rot = tk.Label(row, text="0°", bg=CARD, fg=ACCENT,
                                font=("Segoe UI", 10, "bold"))
        self.lbl_rot.pack(side="left", padx=6)

        self._btn(row, "↻ +90°", self._rotate_cw, color="#21262d", width=9)
        self._btn(row, "↺ -90°", self._rotate_ccw, color="#21262d", width=9)

        row2 = tk.Frame(f, bg=CARD); row2.pack(fill="x", pady=6)
        self.btn_mirror = tk.Button(row2, text="Mirror: OFF", command=self._toggle_mirror,
                                    bg="#21262d", fg=FG2, relief="flat", padx=12, pady=6,
                                    font=("Segoe UI", 9), cursor="hand2",
                                    activebackground=ACCENT)
        self.btn_mirror.pack(side="left", padx=4)

        self._btn(row2, "Reset All", self._full_reset, color=RED, fg="#fff", width=10)

    def _build_instructions(self):
        f = tk.Frame(self.main_frame, bg=BG); f.pack(fill="x", padx=14, pady=(4, 16))
        text = ("Instructions:\n"
                "1. Connect phone to PC via USB cable\n"
                "2. Enable USB Debugging on the phone\n"
                "3. Click 'ADB Forward' button above\n"
                "4. In Virtual Cam app: toggle 'Enable Link', copy the token\n"
                "5. Paste token above, click Connect\n"
                "6. Controls update the camera feed in real-time (<100ms)")
        tk.Label(f, text=text, bg=BG, fg=FG2, font=("Segoe UI", 8),
                 justify="left", anchor="w").pack(anchor="w")

    # ── Connection ────────────────────────────────────────────────────────

    def _adb_forward(self):
        try:
            result = subprocess.run(
                ["adb", "forward", f"tcp:{PORT}", f"tcp:{PORT}"],
                capture_output=True, text=True, timeout=10
            )
            if result.returncode == 0:
                messagebox.showinfo("ADB Forward", f"✓ ADB forward set: tcp:{PORT} → tcp:{PORT}")
            else:
                msg = result.stderr.strip() or result.stdout.strip() or "Unknown error"
                messagebox.showerror("ADB Error", f"ADB forward failed:\n{msg}\n\nMake sure:\n• ADB is installed and in PATH\n• USB Debugging is enabled on phone\n• Phone is connected via USB")
        except FileNotFoundError:
            messagebox.showerror("ADB Not Found",
                "ADB not found. Install Android Platform Tools:\n"
                "https://developer.android.com/tools/releases/platform-tools\n"
                "Then add the folder to your PATH.")
        except subprocess.TimeoutExpired:
            messagebox.showerror("ADB Timeout", "ADB command timed out. Is the phone connected?")

    def _toggle_connect(self):
        if self.connected:
            self._disconnect()
        else:
            threading.Thread(target=self._connect, daemon=True).start()

    def _connect(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(5)
            s.connect((self.host_var.get(), int(self.port_var.get())))
            s.settimeout(None)
            self.sock = s

            # Authenticate
            self._send_raw({"cmd": "auth", "token": self.token_var.get()})
            resp = self._recv_raw()

            if resp and resp.get("status") == "ok":
                self.connected = True
                self.root.after(0, lambda: (
                    self.lbl_status.config(text="● Connected", fg=GREEN),
                    self.btn_connect.config(text="✕ Disconnect")
                ))
            else:
                s.close(); self.sock = None
                msg = (resp or {}).get("message", "unknown error")
                self.root.after(0, lambda: messagebox.showerror(
                    "Auth Error", f"Authentication failed: {msg}\n\nCheck the token shown in Virtual Cam app."))
        except Exception as e:
            if self.sock:
                try: self.sock.close()
                except: pass
                self.sock = None
            self.root.after(0, lambda err=e: messagebox.showerror(
                "Connection Error",
                f"Cannot connect to {self.host_var.get()}:{self.port_var.get()}\n\n"
                f"Error: {err}\n\nMake sure:\n"
                "• ADB forward is set (click 'ADB Forward')\n"
                "• 'Enable Link' is ON in Virtual Cam app\n"
                "• Virtual Cam service is running on phone"))

    def _disconnect(self):
        self.connected = False
        if self.sock:
            try: self.sock.close()
            except: pass
            self.sock = None
        self.lbl_status.config(text="● Disconnected", fg=RED)
        self.btn_connect.config(text="🔌 Connect")

    # ── Protocol ──────────────────────────────────────────────────────────

    def _send_raw(self, obj: dict):
        if self.sock is None: return
        try:
            self.sock.sendall((json.dumps(obj) + "\n").encode("utf-8"))
        except Exception:
            self._disconnect()

    def _recv_raw(self) -> dict | None:
        if self.sock is None: return None
        try:
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = self.sock.recv(4096)
                if not chunk: return None
                buf += chunk
            return json.loads(buf.decode("utf-8").strip())
        except Exception:
            return None

    def _cmd(self, obj: dict):
        if not self.connected: return
        with self.send_lock:
            self._send_raw(obj)
            self._recv_raw()  # consume response

    def _cmd_async(self, obj: dict):
        threading.Thread(target=self._cmd, args=(obj,), daemon=True).start()

    # ── Controls ──────────────────────────────────────────────────────────

    def _on_zoom(self, val):
        z = round(float(val), 2)
        self.lbl_zoom.config(text=f"{z:.1f}×")
        self._cmd_async({"cmd": "zoom", "value": z})

    def _set_zoom(self, val: float):
        self.zoom_var.set(val)
        self.lbl_zoom.config(text=f"{val:.1f}×")
        self._cmd_async({"cmd": "zoom", "value": val})

    def _on_scale(self, val):
        s = round(float(val), 2)
        self.lbl_scale.config(text=f"{int(s * 100)}%")
        self._cmd_async({"cmd": "scale", "value": s})

    def _set_scale(self, val: float):
        self.scale_var.set(val)
        self.lbl_scale.config(text=f"{int(val * 100)}%")
        self._cmd_async({"cmd": "scale", "value": val})

    def _pan(self, dx: int, dy: int):
        step = self.pan_step.get()
        self.pan_x += int(dx * step / 50)
        self.pan_y += int(dy * step / 50)
        self.lbl_pan.config(text=f"Pan: {self.pan_x}, {self.pan_y}")
        self._cmd_async({"cmd": "pan", "x": self.pan_x, "y": self.pan_y})

    def _pan_reset(self):
        self.pan_x = 0; self.pan_y = 0
        self.lbl_pan.config(text="Pan: 0, 0")
        self._cmd_async({"cmd": "pan_reset"})

    def _rotate_cw(self):
        self.rotation = (self.rotation + 90) % 360
        self.lbl_rot.config(text=f"{self.rotation}°")
        self._cmd_async({"cmd": "rotate", "degrees": self.rotation})

    def _rotate_ccw(self):
        self.rotation = (self.rotation - 90) % 360
        self.lbl_rot.config(text=f"{self.rotation}°")
        self._cmd_async({"cmd": "rotate", "degrees": self.rotation})

    def _toggle_mirror(self):
        self.mirror_state = not self.mirror_state
        if self.mirror_state:
            self.btn_mirror.config(text="Mirror: ON", fg=ACCENT, bg=CARD)
        else:
            self.btn_mirror.config(text="Mirror: OFF", fg=FG2, bg="#21262d")
        self._cmd_async({"cmd": "mirror", "enabled": self.mirror_state})

    def _full_reset(self):
        self.pan_x = 0; self.pan_y = 0
        self.rotation = 0
        self.mirror_state = False
        self.zoom_var.set(1.0); self.lbl_zoom.config(text="1.0×")
        self.scale_var.set(1.0); self.lbl_scale.config(text="100%")
        self.lbl_pan.config(text="Pan: 0, 0")
        self.lbl_rot.config(text="0°")
        self.btn_mirror.config(text="Mirror: OFF", fg=FG2, bg="#21262d")
        self._cmd_async({"cmd": "pan_reset"})
        self._cmd_async({"cmd": "rotate", "degrees": 0})
        self._cmd_async({"cmd": "mirror", "enabled": False})


def main():
    root = tk.Tk()
    try:
        from ctypes import windll
        windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    app = ConnectVcam(root)
    root.mainloop()


if __name__ == "__main__":
    main()

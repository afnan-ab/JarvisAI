#!/usr/bin/env python3
"""
JARVIS PC Agent
- Local WebSocket bridge for the Android JARVIS app.
- Accepts multiple connected JARVIS devices.
- Uses a pairing token before commands are accepted.
- Deliberately exposes a small allow-list instead of arbitrary shell execution.
"""

import asyncio
import json
import os
import platform
import subprocess
import webbrowser

import websockets

HOST = os.getenv("JARVIS_HOST", "0.0.0.0")
PORT = int(os.getenv("JARVIS_PORT", "8765"))
TOKEN = os.getenv("JARVIS_TOKEN", "change-me")

clients = set()
paired = set()

async def send(ws, payload):
    await ws.send(json.dumps(payload))

async def broadcast(payload):
    if not clients:
        return
    message = json.dumps(payload)
    await asyncio.gather(
        *(ws.send(message) for ws in list(clients)),
        return_exceptions=True
    )

def open_app(name):
    system = platform.system().lower()
    name = name.lower().strip()
    if name in ("browser", "chrome", "google chrome"):
        webbrowser.open("https://www.google.com")
        return True
    if name in ("calculator", "calc"):
        if system == "windows":
            subprocess.Popen(["calc.exe"])
        elif system == "darwin":
            subprocess.Popen(["open", "-a", "Calculator"])
        else:
            subprocess.Popen(["gnome-calculator"])
        return True
    if name in ("terminal", "cmd"):
        if system == "windows":
            subprocess.Popen(["cmd.exe"])
        elif system == "darwin":
            subprocess.Popen(["open", "-a", "Terminal"])
        else:
            subprocess.Popen(["x-terminal-emulator"])
        return True
    return False

async def handle(ws):
    clients.add(ws)
    await send(ws, {"type": "status", "message": "PC LINK • READY FOR PAIRING"})
    await broadcast({"type": "hub", "clients": len(clients)})

    try:
        async for raw in ws:
            try:
                data = json.loads(raw)
            except Exception:
                await send(ws, {"type": "error", "message": "Invalid JSON"})
                continue

            action = str(data.get("action", "")).lower()
            if action == "pair":
                if data.get("token") == TOKEN:
                    paired.add(ws)
                    await send(ws, {"type": "status", "message": "PC LINK • PAIRED"})
                    await broadcast({"type": "hub", "clients": len(paired)})
                else:
                    await send(ws, {"type": "error", "message": "Pairing token rejected"})
                continue

            if ws not in paired:
                await send(ws, {"type": "error", "message": "Pair the device first"})
                continue

            value = str(data.get("value", "")).strip()

            if action == "open_app":
                ok = open_app(value)
                await send(ws, {"type": "status", "message": "Opened " + value if ok else "Unsupported PC app"})
            elif action == "open_url":
                if value.startswith(("https://", "http://")):
                    webbrowser.open(value)
                    await send(ws, {"type": "status", "message": "Browser opened"})
                else:
                    await send(ws, {"type": "error", "message": "Only http/https URLs are allowed"})
            elif action == "ping":
                await send(ws, {"type": "status", "message": "PC ONLINE • " + platform.system()})
            elif action == "disconnect":
                break
            else:
                await send(ws, {"type": "error", "message": "Unsupported PC action"})
    finally:
        clients.discard(ws)
        paired.discard(ws)
        await broadcast({"type": "hub", "clients": len(paired)})

async def main():
    async with websockets.serve(handle, HOST, PORT):
        print(f"JARVIS PC Agent listening on ws://{HOST}:{PORT}")
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())

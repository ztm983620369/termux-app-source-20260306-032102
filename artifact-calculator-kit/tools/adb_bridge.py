#!/usr/bin/env python3
import argparse
import os
import pathlib
import socket
import struct
import sys
import time


ADB_HOST = "127.0.0.1"
ADB_PORT = 5037


class AdbError(RuntimeError):
    pass


def _read_exact(sock: socket.socket, size: int) -> bytes:
    out = bytearray()
    while len(out) < size:
        chunk = sock.recv(size - len(out))
        if not chunk:
            raise AdbError(f"socket closed while reading {size} bytes")
        out.extend(chunk)
    return bytes(out)


def _send_service(sock: socket.socket, service: str) -> None:
    payload = service.encode("utf-8")
    sock.sendall(f"{len(payload):04x}".encode("ascii") + payload)
    status = _read_exact(sock, 4)
    if status == b"OKAY":
        return
    if status != b"FAIL":
        raise AdbError(f"unexpected adb status: {status!r}")
    msg_len = int(_read_exact(sock, 4).decode("ascii"), 16)
    msg = _read_exact(sock, msg_len).decode("utf-8", "replace")
    raise AdbError(msg)


def _host_query(service: str) -> str:
    with socket.create_connection((ADB_HOST, ADB_PORT), timeout=8) as sock:
        _send_service(sock, service)
        length = int(_read_exact(sock, 4).decode("ascii"), 16)
        data = _read_exact(sock, length)
        return data.decode("utf-8", "replace")


def _open_transport(serial: str | None) -> socket.socket:
    sock = socket.create_connection((ADB_HOST, ADB_PORT), timeout=8)
    if serial:
        _send_service(sock, f"host:transport:{serial}")
    else:
        _send_service(sock, "host:transport-any")
    return sock


def adb_devices() -> str:
    return _host_query("host:devices-l")


def adb_shell(cmd: str, serial: str | None = None) -> str:
    with _open_transport(serial) as sock:
        _send_service(sock, f"shell:{cmd}")
        out = bytearray()
        while True:
            chunk = sock.recv(8192)
            if not chunk:
                break
            out.extend(chunk)
        return out.decode("utf-8", "replace")


def _sync_send_packet(sock: socket.socket, ident: bytes, payload: bytes = b"") -> None:
    if len(ident) != 4:
        raise ValueError("ident must be 4 bytes")
    sock.sendall(ident + struct.pack("<I", len(payload)) + payload)


def adb_push(local_path: str, remote_path: str, serial: str | None = None) -> None:
    local = pathlib.Path(local_path)
    if not local.is_file():
        raise AdbError(f"local file not found: {local}")

    with _open_transport(serial) as sock:
        _send_service(sock, "sync:")

        mode = 0o100644
        send_spec = f"{remote_path},{mode}".encode("utf-8")
        _sync_send_packet(sock, b"SEND", send_spec)

        with local.open("rb") as fh:
            while True:
                chunk = fh.read(64 * 1024)
                if not chunk:
                    break
                _sync_send_packet(sock, b"DATA", chunk)

        sock.sendall(b"DONE" + struct.pack("<I", int(time.time())))

        ident = _read_exact(sock, 4)
        size = struct.unpack("<I", _read_exact(sock, 4))[0]
        if ident == b"OKAY":
            return
        if ident == b"FAIL":
            message = _read_exact(sock, size).decode("utf-8", "replace")
            raise AdbError(f"push failed: {message}")
        raise AdbError(f"push failed: unexpected sync response {ident!r}, size={size}")


def adb_install(apk_path: str, serial: str | None = None) -> str:
    local_apk = pathlib.Path(apk_path)
    if not local_apk.is_file():
        raise AdbError(f"apk not found: {local_apk}")

    remote = f"/data/local/tmp/{local_apk.name}"
    adb_push(str(local_apk), remote, serial=serial)
    output = adb_shell(f"pm install -r '{remote}'", serial=serial)
    return output.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description="ADB bridge over localhost:5037 without adb.exe")
    parser.add_argument("--serial", default=None, help="device serial (optional)")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("devices")

    shell_p = sub.add_parser("shell")
    shell_p.add_argument("command", help="shell command")

    push_p = sub.add_parser("push")
    push_p.add_argument("local")
    push_p.add_argument("remote")

    install_p = sub.add_parser("install")
    install_p.add_argument("apk")

    args = parser.parse_args()

    try:
        if args.cmd == "devices":
            print(adb_devices().strip())
            return 0
        if args.cmd == "shell":
            print(adb_shell(args.command, serial=args.serial).rstrip())
            return 0
        if args.cmd == "push":
            adb_push(args.local, args.remote, serial=args.serial)
            print(f"PUSH OK: {args.local} -> {args.remote}")
            return 0
        if args.cmd == "install":
            result = adb_install(args.apk, serial=args.serial)
            print(result)
            return 0
        raise AdbError(f"unknown command: {args.cmd}")
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

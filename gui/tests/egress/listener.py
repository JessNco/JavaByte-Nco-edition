#!/usr/bin/env python3
"""Fake network for tests/egress: a DNS server and listeners that log every attempt to reach them.

Runs inside an empty network namespace, so the only network that exists is this one. Any
name lookup or connection the app makes ends up here and is written, one JSON object per line,
to the log file given as the first argument.

- DNS (UDP 53): logs each query; answers 127.0.0.1 for *.leak.test so that a lookup that
  succeeds is followed by a connection, which is logged too.
- TCP 80, 443, PORT, PORT+1: logs each connection and the first line it sends.
- UDP 3478: logs anything (STUN, as WebRTC would send).
- TCP 9: the app's black-hole proxy. Requests that arrive here were caught by the proxy and
  went nowhere else; they are logged as "blackhole" and are not leaks.
"""
import asyncio
import json
import struct
import sys
import time

LOG = open(sys.argv[1], "a", buffering=1)
PORT = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 8099
# --no-dns: for machines where the system's name lookups cannot be pointed here (Windows CI).
WITH_DNS = "--no-dns" not in sys.argv


def log(kind, **fields):
    LOG.write(json.dumps({"time": round(time.time(), 3), "kind": kind, **fields}) + "\n")


def query_name(packet):
    labels, i = [], 12
    while i < len(packet) and packet[i]:
        length = packet[i]
        labels.append(packet[i + 1:i + 1 + length].decode("ascii", "replace"))
        i += 1 + length
    return ".".join(labels), i + 1


class Dns(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if len(data) < 12:
            return
        name, end = query_name(data)
        qtype = struct.unpack(">H", data[end:end + 2])[0] if end + 2 <= len(data) else 0
        log("dns", name=name, qtype=qtype)
        header = bytearray(data[:12])
        question = data[12:end + 4]
        answer_a = name.endswith(".leak.test") and qtype == 1
        header[2] = 0x81  # response, recursion desired
        header[3] = 0x80 if answer_a else 0x83  # NXDOMAIN unless we answer
        header[6:8] = struct.pack(">H", 1 if answer_a else 0)
        header[8:12] = b"\0\0\0\0"
        reply = bytes(header) + question
        if answer_a:
            reply += b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 5, 4) + bytes([127, 0, 0, 1])
        self.transport.sendto(reply, addr)


class Udp(asyncio.DatagramProtocol):
    def __init__(self, port):
        self.port = port

    def datagram_received(self, data, addr):
        log("udp", port=self.port, size=len(data), source=addr[0])


async def tcp(reader, writer, port):
    first = b""
    try:
        first = await asyncio.wait_for(reader.readline(), 2)
    except Exception:
        pass
    log("tcp", port=port, first_line=first[:200].decode("latin-1").strip())
    try:
        writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nAccess-Control-Allow-Origin: *\r\n\r\nok")
        await writer.drain()
    finally:
        writer.close()


async def blackhole(reader, writer):
    first = b""
    try:
        first = await asyncio.wait_for(reader.readline(), 2)
    except Exception:
        pass
    # Close without answering, as the closed port would in real use.
    log("blackhole", port=9, first_line=first[:200].decode("latin-1").strip())
    writer.close()


async def main():
    loop = asyncio.get_running_loop()
    if WITH_DNS:
        await loop.create_datagram_endpoint(Dns, local_addr=("127.0.0.1", 53))
    await loop.create_datagram_endpoint(lambda: Udp(3478), local_addr=("127.0.0.1", 3478))
    for port in sorted({80, 443, PORT, PORT + 1}):
        await asyncio.start_server(lambda r, w, p=port: tcp(r, w, p), "127.0.0.1", port)
    await asyncio.start_server(blackhole, "127.0.0.1", 9)
    log("ready")
    await asyncio.Event().wait()


asyncio.run(main())

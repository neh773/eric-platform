"""Minimal Sparkplug B payload codec for the edge-node simulator.

Wire format is the Eclipse Sparkplug protobuf payload (specification 3.0):
https://sparkplug.eclipse.org/specification/version/3.0.0/

Only the fields this simulator publishes are implemented: payload timestamp,
seq, and metrics with a name, datatype, and one of int/long/float/double/
boolean/string. DataSet, Template, metadata and properties are not encoded.
Unknown fields are skipped on decode so a fuller payload still yields the
metrics we understand.
"""

from __future__ import annotations

import struct
from typing import Any

TYPE_INT32 = 3
TYPE_INT64 = 4
TYPE_UINT64 = 8
TYPE_FLOAT = 9
TYPE_DOUBLE = 10
TYPE_BOOLEAN = 11
TYPE_STRING = 12


def _varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint must be >= 0")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _key(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def _read_varint(buf: bytes, index: int) -> tuple[int, int]:
    shift = 0
    value = 0
    while True:
        if index >= len(buf) or shift > 63:
            raise ValueError("truncated varint")
        byte = buf[index]
        index += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, index
        shift += 7


def _skip(buf: bytes, index: int, wire: int) -> int:
    if wire == 0:
        _value, index = _read_varint(buf, index)
        return index
    if wire == 1:
        return index + 8
    if wire == 5:
        return index + 4
    if wire == 2:
        length, index = _read_varint(buf, index)
        return index + length
    raise ValueError(f"unsupported wire type {wire}")


def _encode_metric(metric: dict[str, Any]) -> bytes:
    body = bytearray()
    name = metric["name"].encode()
    body += _key(1, 2) + _varint(len(name)) + name
    body += _key(4, 0) + _varint(int(metric["datatype"]))
    if "int_value" in metric:
        body += _key(10, 0) + _varint(int(metric["int_value"]))
    elif "long_value" in metric:
        body += _key(11, 0) + _varint(int(metric["long_value"]))
    elif "float_value" in metric:
        body += _key(12, 5) + struct.pack("<f", float(metric["float_value"]))
    elif "double_value" in metric:
        body += _key(13, 1) + struct.pack("<d", float(metric["double_value"]))
    elif "bool_value" in metric:
        body += _key(14, 0) + _varint(1 if metric["bool_value"] else 0)
    elif "string_value" in metric:
        raw = str(metric["string_value"]).encode()
        body += _key(15, 2) + _varint(len(raw)) + raw
    else:
        raise ValueError(f"metric {metric['name']} has no value")
    return bytes(body)


def encode_payload(timestamp: int, seq: int, metrics: list[dict[str, Any]]) -> bytes:
    body = bytearray()
    body += _key(1, 0) + _varint(int(timestamp))
    for metric in metrics:
        encoded = _encode_metric(metric)
        body += _key(2, 2) + _varint(len(encoded)) + encoded
    body += _key(3, 0) + _varint(int(seq) & 0xFF)
    return bytes(body)


def _decode_metric(buf: bytes) -> dict[str, Any]:
    metric: dict[str, Any] = {}
    index = 0
    while index < len(buf):
        key, index = _read_varint(buf, index)
        field, wire = key >> 3, key & 0x07
        if field == 1 and wire == 2:
            length, index = _read_varint(buf, index)
            metric["name"] = buf[index : index + length].decode()
            index += length
        elif field == 4 and wire == 0:
            metric["datatype"], index = _read_varint(buf, index)
        elif field == 10 and wire == 0:
            metric["int_value"], index = _read_varint(buf, index)
        elif field == 11 and wire == 0:
            metric["long_value"], index = _read_varint(buf, index)
        elif field == 12 and wire == 5:
            metric["float_value"] = struct.unpack_from("<f", buf, index)[0]
            index += 4
        elif field == 13 and wire == 1:
            metric["double_value"] = struct.unpack_from("<d", buf, index)[0]
            index += 8
        elif field == 14 and wire == 0:
            value, index = _read_varint(buf, index)
            metric["bool_value"] = bool(value)
        elif field == 15 and wire == 2:
            length, index = _read_varint(buf, index)
            metric["string_value"] = buf[index : index + length].decode()
            index += length
        else:
            index = _skip(buf, index, wire)
    return metric


def decode_payload(raw: bytes) -> dict[str, Any]:
    payload: dict[str, Any] = {"timestamp": 0, "seq": 0, "metrics": []}
    index = 0
    while index < len(raw):
        key, index = _read_varint(raw, index)
        field, wire = key >> 3, key & 0x07
        if field == 1 and wire == 0:
            payload["timestamp"], index = _read_varint(raw, index)
        elif field == 3 and wire == 0:
            payload["seq"], index = _read_varint(raw, index)
        elif field == 2 and wire == 2:
            length, index = _read_varint(raw, index)
            payload["metrics"].append(_decode_metric(raw[index : index + length]))
            index += length
        else:
            index = _skip(raw, index, wire)
    return payload


def _golden() -> bytes:
    return encode_payload(1000, 1, [{"name": "Value", "datatype": TYPE_DOUBLE, "double_value": 1.0}])


if __name__ == "__main__":
    import json
    import sys

    command = sys.argv[1]
    if command == "encode-golden":
        sys.stdout.write(_golden().hex())
    elif command == "decode-hex":
        json.dump(decode_payload(bytes.fromhex(sys.stdin.read().strip())), sys.stdout)
    else:
        raise SystemExit(f"unknown command {command}")

---
title: Device connections
sidebar_label: Device connections
---

# Device connections

**Connections** lists every device and whether it is connected, connecting, faulted, or disabled. **Devices**, on the same page, is where you add one.

## Add a device

1. Click **New device**.
2. Type a short lowercase name.
3. Choose the type and fill the fields that type asks for.
4. Click **Save device**. The status list updates on its own.

**Edit as JSON** shows the stored connection. Saving the form keeps settings the form does not list. Saving the JSON replaces the connection with that text.

## Types

| Type | What you set |
|---|---|
| Modbus | Host, port (502), TCP or RTU-over-TCP, unit ID, word order, and byte order. |
| EtherNet/IP | Host, port (44818), profile (current Logix, legacy ControlLogix, legacy CompactLogix, or Micro800), slot, and an optional connection path such as `1,0` for a backplane hop. |
| Siemens S7 | Host, port (102), model (300, 400, 1200, 1500), rack, and slot. |
| OPC UA client | Endpoint, starting with `opc.tcp://`, plus the security policy and mode. None is for a server that does not require a certificate. |
| BACnet local | This gateway's device name and instance. Create this before a remote device. |
| BACnet remote | The local device name, the remote instance, and the host. |
| DNP3 | Host, port (20000), master address, and outstation address. |
| Simulator | A built-in program: generic, dairy, slc, or none. No hardware is required. |
| UDP/TCP | Protocol, listen address, and one or more ports. |

## Modbus addresses

Tags on a Modbus device use a prefix and a number:

- `HR` holding register, `IR` input register, `C` coil, `DI` discrete input.
- `HR1` is the first holding register when one-based addressing is in use.
- A 32-bit value uses the word order and byte order on the device.

The driver builds its address list from the tags that point at it. Word order and byte order on this page are what those values use.

## If it stays disconnected

The status row's detail is the driver's last error. Check the host, port, and, for OPC UA, that the endpoint and security mode match the server. A disabled device stays in the list and does not connect.

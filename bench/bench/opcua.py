"""OPC UA reads for the load harness.

the reference platform 8.3.10 publishes no REST tag-read route. Reads go through the reference platform's
own OPC UA server once tag providers are exposed. The same client is used for
our gateway when ``--read-via-opcua`` is set, for the server M2.4 will add.

The client is asyncua, imported only when a read opens a session. Unit tests
pass a fake client and do not install the package. asyncua is LGPL-3.0-or-later
and stays in this harness; it is not linked into the gateway.
"""

from __future__ import annotations

import calendar
from dataclasses import dataclass


class OpcUaError(Exception):
    pass


@dataclass(frozen=True)
class AttributeValue:
    value: object
    good: bool
    status: str
    timestamp_ms: int | None


def reference_node_id(provider: str, name: str) -> str:
    """Node id the reference platform serves for a tag when Expose Tag Providers is on.

    8.3.10 puts the provider's tags in namespace 2 as ``ns=2;s=[provider]name``.
    """
    return f"ns=2;s=[{provider}]{name}"


def format_node_id(template: str, *, provider: str, name: str, path: str, index: int) -> str:
    """Fill ``oip.opcuaNodeId``. Placeholders: provider, name, path, index."""
    try:
        return template.format(provider=provider, name=name, path=path, index=index)
    except (KeyError, IndexError, ValueError) as exc:
        raise OpcUaError(f"OPC UA node id template {template!r} could not be filled: {exc}") from exc


def datetime_to_ms(value) -> int | None:
    if value is None or not hasattr(value, "utctimetuple"):
        return None
    if getattr(value, "tzinfo", None) is not None:
        return int(value.timestamp() * 1000)
    micros = int(getattr(value, "microsecond", 0) or 0) // 1000
    return int(calendar.timegm(value.utctimetuple()) * 1000) + micros


def _status_good(status) -> bool:
    if status is None:
        return False
    check = getattr(status, "is_good", None)
    if callable(check):
        return bool(check())
    if isinstance(check, bool):
        return check
    return False


def _status_name(status) -> str:
    if status is None:
        return "none"
    name = getattr(status, "name", None)
    if name:
        return str(name)
    return str(status)


def attribute_from_datavalue(data_value) -> AttributeValue:
    """Read the fields a stock asyncua DataValue exposes. Missing fields are a bad value."""
    status = getattr(data_value, "StatusCode", None)
    variant = getattr(data_value, "Value", None)
    raw = getattr(variant, "Value", None) if variant is not None else None
    timestamp = getattr(data_value, "SourceTimestamp", None) or getattr(data_value, "ServerTimestamp", None)
    return AttributeValue(raw, _status_good(status), _status_name(status), datetime_to_ms(timestamp))


class OpcUaReader:
    """One None-security session. ``client_factory`` is for tests.

    Our gateway rejects anonymous tokens. Pass the bench user when the server
    requires a username identity. The password is not included in errors.
    """

    def __init__(
        self,
        endpoint: str,
        client_factory=None,
        timeout: float = 30,
        username: str | None = None,
        password: str | None = None,
    ) -> None:
        self.endpoint = endpoint
        self._username = username
        self._password = password
        self._factory = client_factory
        self._timeout = timeout
        self._client = None

    def read(self, node_ids: list[str]) -> list[AttributeValue]:
        client = self._client_or_connect()
        try:
            nodes = [client.get_node(node_id) for node_id in node_ids]
            values = client.read_attributes(nodes)
        except OpcUaError:
            raise
        except Exception as exc:
            raise OpcUaError(f"OPC UA read failed: {exc}") from exc
        if values is None:
            values = []
        return [attribute_from_datavalue(value) for value in values]

    def close(self) -> None:
        client = self._client
        self._client = None
        if client is None:
            return
        disconnect = getattr(client, "disconnect", None)
        if disconnect is None:
            return
        try:
            disconnect()
        except Exception:
            return

    def _client_or_connect(self):
        if self._client is not None:
            return self._client
        if self._factory is not None:
            self._client = self._factory(self.endpoint)
            self._apply_identity(self._client)
        else:
            try:
                from asyncua.sync import Client
            except ImportError as exc:
                raise OpcUaError(
                    "Reading tags through an OPC UA server needs the optional asyncua package. "
                    "From the bench directory: python3 -m pip install -r requirements-reference.txt. "
                    "The gateway does not depend on it."
                ) from exc
            client = Client(self.endpoint, timeout=self._timeout)
            self._apply_identity(client)
            try:
                client.connect()
            except Exception as exc:
                raise OpcUaError(f"OPC UA connect to {self.endpoint} failed: {self._redact(exc)}") from exc
            self._client = client
        connect = getattr(self._client, "connect", None)
        # A factory may return an already-connected fake. A real stand-in can still expose connect().
        if self._factory is not None and connect is not None and self._client is not None:
            try:
                connect()
            except Exception as exc:
                self._client = None
                raise OpcUaError(f"OPC UA connect to {self.endpoint} failed: {self._redact(exc)}") from exc
        return self._client

    def _apply_identity(self, client) -> None:
        if not self._username or client is None:
            return
        set_user = getattr(client, "set_user", None)
        set_password = getattr(client, "set_password", None)
        if set_user is not None:
            set_user(self._username)
        if set_password is not None:
            set_password(self._password or "")

    def _redact(self, exc: Exception) -> str:
        text = str(exc)
        if self._password and self._password in text:
            text = text.replace(self._password, "[redacted]")
        return text

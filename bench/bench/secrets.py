"""Admin password loading. The password is never placed on a command line."""

from __future__ import annotations


class SecretError(Exception):
    pass


def read_admin_password(text: str, source: str = "password file") -> str:
    """A one-line password, or an env file that sets ``OIP_ADMIN_PASSWORD``.

    The returned string is the password. Callers must not log it or pass it as a
    process argument. ``source`` is a path or label used only in errors.
    """
    lines = []
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        lines.append(stripped)
    for line in lines:
        body = line[len("export "):].strip() if line.startswith("export ") else line
        if body.startswith("OIP_ADMIN_PASSWORD="):
            value = _unquote(body.split("=", 1)[1].strip())
            _reject_bad_password(value, source)
            return value
    if any("=" in line for line in lines):
        raise SecretError(f"{source} has no OIP_ADMIN_PASSWORD entry")
    if len(lines) != 1:
        raise SecretError(f"{source} must be one password line or an env file with OIP_ADMIN_PASSWORD")
    value = lines[0]
    _reject_bad_password(value, source)
    return value


def password_env_text(password: str) -> str:
    """Text for ``docker run --env-file``. Docker does not strip quotes, so the value is raw."""
    _reject_bad_password(password, "password")
    return f"OIP_ADMIN_PASSWORD={password}\n"


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def _reject_bad_password(value: str, source: str) -> None:
    if not value:
        raise SecretError(f"{source} has an empty password")
    if any(ch in value for ch in ("\n", "\r", "\x00")):
        raise SecretError(f"{source} password must be a single line")

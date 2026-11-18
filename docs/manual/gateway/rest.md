# REST API

The gateway exposes its configuration and runtime over HTTP under `/api/v1`. The admin UI and Control Canvas use this API. You can call it from a script.

## Sign in

```
POST /api/v1/auth/login
{"username":"admin","password":"<the password you set>"}
```

The response sets a session cookie. Send that cookie on later requests. `GET /api/v1/auth/session` returns the signed-in user. `POST /api/v1/auth/logout` ends it.

There is no API key in this build. Do not put the password in a file you commit.

## Projects

| Method and path | Who | What |
| --- | --- | --- |
| `GET /api/v1/projects` | administrator | List projects. |
| `POST /api/v1/projects` | administrator | Create a project. |
| `PATCH /api/v1/projects/{name}` | administrator | Change settings. Send `If-Match` with the project version. `publicHmi` true makes that project's HMI anonymous. |
| `DELETE /api/v1/projects/{name}` | administrator | Delete the project. |

## Tags

| Method and path | Who | What |
| --- | --- | --- |
| `POST /api/v1/tags/read` | a signed-in user, or Public HMI | Body is a list of tag paths. The reply is value and quality. |
| `POST /api/v1/tags/write` | a signed-in user, or Public HMI | Body is a path and a value. |

Public HMI can call these tag routes and the HMI routes for a project that turned the setting on. It cannot call the project list or the admin routes. Those return 401.

## History and alarms

`GET` history for a tag path returns samples in a time window. Alarm routes list active alarms and accept ack and shelve for a signed-in user or for Public HMI.

## Examples

| Method and path | Who | What |
| --- | --- | --- |
| `GET /api/v1/examples` | administrator | List example projects the gateway can see (`OIP_EXAMPLES` or `./examples`). |
| `POST /api/v1/examples/{id}/install` | administrator | Import that example. Add `?replace=true` to replace an existing project of the same name. |

## Logic

`/api/v1/logic/programs` lists Structured Text programs. Save and check go through the same prefix. Check runs RuSTy. Deploy loads the result into the soft-PLC.

## WebSocket

`/api/v1/ws` is the live tag stream the HMI uses. A Public HMI session may open it for a project that allows anonymous access.

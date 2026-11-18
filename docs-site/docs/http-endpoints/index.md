---
title: HTTP endpoints
sidebar_label: HTTP endpoints
---

# HTTP endpoints

**HTTP Endpoints** publishes a small page or API on this gateway. Each one is served at `/system/webdev/gateway/` followed by the path you choose.

## Add an endpoint

1. Open **HTTP Endpoints**.
2. Name it with lowercase letters, digits, and dashes.
3. Set the path. Use a name such as `status` or `api/reading`. Do not use `.` or `..`.
4. Choose the type.
   - **Text** returns the body you type. It answers GET.
   - **Python** runs a function named `doGet`, `doPost`, `doPut`, `doPatch`, `doDelete`, or `doHead`. The function receives the request and returns a string, or a dictionary with `status`, `contentType`, and `body`.
   - **Mounted folder** serves files from a folder under the gateway's webdev directory.
5. Turn on **Require sign-in** when the page should not be public. Turn on **Require HTTPS** when plain HTTP must be refused.
6. Click **Save endpoint**. **Edit as JSON** shows the same fields and can hold anything the form does not list.

Open the address shown in the table to try the page.

The same page lists every generated gateway route, which is also the JSON document at `/api/docs`.

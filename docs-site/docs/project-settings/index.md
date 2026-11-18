---
title: Project settings
sidebar_label: Project settings
---

# Project settings

Project settings are in Control Canvas, in the **Project** panel. They apply to sessions of this project.

## Touchscreen mode

Turn on **Touchscreen mode** and click **Save project settings**. Inputs in a session of this project then open an on-screen keyboard, and the page is marked for larger touch targets.

A single session can still turn the keyboard on with the session value `touchscreen` set to true, or by opening the session with `?touchscreen=on`, even when the project switch is off.

## Other settings

- **Default tag provider** and **Default database** are where new references start.
- **Session roles** and **Session security levels** limit who can open the project. **Require every session level** means the person needs all of the listed levels, not just one.
- **Session theme** is `daylight` for Light. Leave it blank for Dark.
- **Session locale** is a language tag such as `en-US`.
- **Permissions** lists the Control Canvas roles required to view, save, delete, and protect resources. Leave a box blank to let every Control Canvas user do that.

The same settings can be changed through the project API. A field you leave out of a change keeps its current value.

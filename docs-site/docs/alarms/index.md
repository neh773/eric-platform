---
title: Alarm notification
sidebar_label: Alarm notification
---

# Alarm notification

**Alarming** is where you say who is on call and how an alarm reaches them. Pipelines that are already running are listed at the top.

## Roster

A roster is an ordered list of user names. Click **New roster**, type the names separated by commas, and save. The names are the gateway user names, in lowercase.

## Schedule

A schedule says when those people are on call. **Weekday hours** is a range such as `08:00-17:00`. A contact can name the schedule.

## Contact

A contact adds an email address or a phone number for one user. The contact's name is the user name. Notifications use these addresses.

## Pipeline

A pipeline is the path an alarm takes.

1. Click **New pipeline**.
2. Name the roster to notify, and an email profile if the message should go out by email.
3. Write the message. `{name}` and `{eventState}` are filled in from the alarm.
4. A delay, in seconds, waits before the pipeline finishes. Leave it blank to finish as soon as the notice is sent.
5. **Stop when acknowledged** drops the alarm from the pipeline once someone acknowledges it.

The saved pipeline is a short chain: start, notify, an optional delay, and end. **Edit as JSON** is how you add a splitter, a switch, an expression, a jump, or an escalation. Saving the form keeps those extra blocks only when you have not replaced the block list; use the JSON view to edit a pipeline that is more than this chain.

## One-way email

A one-way email profile is still stored as configuration. The pipeline's email profile field names it. The profile's password is a secret reference, never the password itself.

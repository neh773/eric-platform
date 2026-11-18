---
title: Getting started
sidebar_label: From zero to a live screen
---

# From zero to a live screen

This path takes a new gateway to a value you can see. You do not write code. Each step names the screen you click.

## 1. Start the gateway

Use the install that matches the machine: the Linux package, the Windows service, a container, or the Helm chart. When the process is up, open the admin address in a browser. The health line on Overview reads **UP**.

## 2. Sign in

The first page is **Sign in**. Use the administrator account created at install. After sign-in you land on **Home**, which lists the projects you can open and the first-run steps that are still unfinished.

## 3. Make a tag

Open **Tags**. Create a memory tag, give it a path such as `plant/tank/level`, and set a starting number. The row shows the value, the quality **Good**, and the time it last changed. Memory tags live in the gateway, so you can practice before a device is connected.

A device connection (Modbus, OPC UA, and the other drivers) is listed under **Connections**. Adding a new device from a guided form is still being built. Until that form is there, a device is added through the config API, and this page will be updated when the form lands.

## 4. Draw a screen

Open **Home** and launch **Control Canvas** for the project. In the left rail choose **Add**, then place a numeric display on the canvas. Select it. In the property editor on the right, bind its value to the tag you created. Choose **Save**.

## 5. Look at it live

Open the project from **Home**. The numeric display shows the tag's value. Change the tag on the **Tags** page and watch the screen follow.

## 6. Keep history and raise an alarm

On the tag, turn on history if you want the value kept over time, and add an alarm if the value should call for attention. **Store and Forward** shows whether those writes are reaching the database. **Alarming** holds the pipelines and rosters that decide who is told.

The next pages of this manual go into each of those screens one task at a time.

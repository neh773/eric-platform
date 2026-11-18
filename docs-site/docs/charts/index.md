---
title: Charts
sidebar_label: Charts
---

# Charts

A chart runs a sequence on the gateway. Use a chart when the work is steps and transitions. Use a script when the work is one action.

## Design a chart

1. Open **Charts** and stay on **Design**.
2. Name the chart with lowercase letters, digits, and dashes.
3. Choose **Callable**, **Run always**, or **Disabled**. Callable waits until something starts it. Run always starts on its own. Disabled does not run.
4. List parameters and variables as comma-separated names. A parameter is an input. A variable belongs to the chart.
5. Fill the pieces you need.
   - **Action script** is one step.
   - **Enclosed chart** runs another chart and waits for it.
   - **Parallel left** and **Parallel right** run two scripts at the same time. Fill both, or neither.
   - **Transition** is the expression that lets the chart continue. `true` continues at once.
   - **Jump back to the start** returns to an anchor after the transition, instead of ending.
6. Click **Save chart**. **Edit as JSON** shows the begin step, the links, and the end step, and can hold anything the form does not list.

## Watch a running chart

Open **Monitor**. The active step is marked. **Start** runs a callable chart. **Pause**, **Resume**, and **Cancel** act on that run. A reader who cannot change tags does not see those buttons.

# Brownsville wastewater example

Brownsville is a fictional collection and treatment system used to try the HMI. It is an example project. A new gateway does not contain it, and a project you create yourself does not open without a sign-in.

It has twelve lift stations, three basins, two plants, force mains drawn on equipment ports, faceplates for pumps, valves, instruments, wet wells, and the dissolved-oxygen loop, a street map, and a timer that moves the levels.

## Import

The gateway has to be able to see the example directory. Set `OIP_EXAMPLES` to the folder that contains `brownsville-wastewater`, or run the gateway from a checkout that has `examples/` in the working directory.

Then either:

- Gateway admin → Projects → **Import Brownsville wastewater**. The project name is `brownsville`.
- Control Canvas → File → **New from example**.
- PowerShell, from `examples/brownsville-wastewater`:

```
./import.ps1
```

That calls `POST /api/v1/examples/brownsville-wastewater/install`. Add `-Replace` to replace a project that is already there.

The example turns on **Public HMI** for itself. Opening http://127.0.0.1:8090/hmi/brownsville/ then needs no sign-in. Tag read and write for that session stay on the HMI. `/admin` still asks for an administrator. Any other project leaves the public-HMI box off, so it still asks for a sign-in.

## Remove

On the Projects page, delete `brownsville`. That removes its screens. The tags, the timer, and the historian settings are gateway resources named for Brownsville. Delete those from the tag browser, the gateway event `brownsville-sim`, and the history settings whose paths start with `Brownsville/`, if you want them gone too. Deleting the project does not delete the example files, so you can import it again.

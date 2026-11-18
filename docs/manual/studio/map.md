# Map

Control Canvas has two map components. Use the Leaflet map when you want a street basemap. Use the other map when you want a drawn geographic view without tile servers.

## Leaflet map

1. In a view, place **Leaflet map**.
2. Set the centre latitude, centre longitude, and zoom.
3. Leave the tile template at the OpenStreetMap default unless you run your own tile server. The attribution must stay visible. The default text is `© OpenStreetMap`.
4. Markers are a JSON list. Each marker has `id`, `lat`, `lon`, `title`, and optionally `path` (a tag whose value is drawn on the marker), `max`, `high`, `unit`, and `href` (a page to open when the marker is tapped).
5. Lines are a JSON list of `{points, color}`. Each point is a latitude and longitude pair.

Pinch and drag are the map's own gestures. Process symbols on the rest of the view stay the native ERIC symbols. The street map is the only place a web map library is used.

## Power user

The component type is `leaflet-map`. Properties are `centerLat`, `centerLon`, `zoom`, `tileUrl`, `attribution`, `markers`, and `lines`. `markers` and `lines` are JSON strings. A heavy tile load belongs on your own tile server. The public OpenStreetMap servers are for light use.

The plain `map` component takes `tileUrl`, `centerLat`, `centerLon`, and `zoom` as well, and draws layers, markers, and shapes in the view's own coordinates. Use it when you do not want a tile attribution strip.
